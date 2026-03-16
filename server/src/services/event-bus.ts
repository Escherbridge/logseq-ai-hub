import { sseManager as defaultSseManager } from "./sse";
import type { Database } from "bun:sqlite";
import type { HubEvent, SSEEvent } from "../types";
import { insertEvent, queryEvents, pruneEvents, countEvents } from "../db/events";

type SSEBroadcaster = { broadcast(event: SSEEvent): void };

export class EventBus {
  private db: Database;
  private sse: SSEBroadcaster;
  private dedupeMap: Map<string, number> = new Map();
  private static DEDUPE_WINDOW_MS = 1000;

  constructor(db: Database, sse?: SSEBroadcaster) {
    this.db = db;
    this.sse = sse || defaultSseManager;
  }

  publish(event: Partial<HubEvent>): HubEvent {
    const now = Date.now();
    const hubEvent: HubEvent = {
      id: crypto.randomUUID(),
      type: event.type || "unknown",
      source: event.source || "unknown",
      timestamp: new Date().toISOString(),
      data: event.data || {},
      ...(event.metadata ? { metadata: event.metadata } : {}),
    };

    // Duplicate detection: composite key = type + source + sorted-keys JSON of data
    const dataHash = JSON.stringify(
      Object.keys(hubEvent.data)
        .sort()
        .reduce<Record<string, unknown>>((acc, key) => {
          acc[key] = hubEvent.data[key];
          return acc;
        }, {})
    );
    const dedupeKey = `${hubEvent.type}:${hubEvent.source}:${dataHash}`;

    const lastSeen = this.dedupeMap.get(dedupeKey);
    if (lastSeen !== undefined && now - lastSeen < EventBus.DEDUPE_WINDOW_MS) {
      // Duplicate within window -- skip store and broadcast
      return { ...hubEvent, _deduplicated: true } as HubEvent & { _deduplicated: boolean };
    }
    this.dedupeMap.set(dedupeKey, now);

    // Clean up old entries from dedup map periodically
    if (this.dedupeMap.size > 1000) {
      for (const [key, ts] of this.dedupeMap) {
        if (now - ts > EventBus.DEDUPE_WINDOW_MS) {
          this.dedupeMap.delete(key);
        }
      }
    }

    // Store the event
    insertEvent(this.db, hubEvent);

    // Chain depth guard: if chain_depth >= 5, store but skip broadcast
    if (hubEvent.metadata?.chain_depth !== undefined && hubEvent.metadata.chain_depth >= 5) {
      console.warn(
        `[EventBus] Chain depth ${hubEvent.metadata.chain_depth} >= 5 for event ${hubEvent.id}, suppressing broadcast`
      );
      return hubEvent;
    }

    // Broadcast via SSE -- nest under payload key to avoid type collision
    this.sse.broadcast({
      type: "hub_event",
      data: { payload: hubEvent },
    });

    return hubEvent;
  }

  prune(retentionDays: number): number {
    return pruneEvents(this.db, retentionDays);
  }

  query(opts: {
    type?: string;
    source?: string;
    since?: string;
    limit?: number;
    offset?: number;
  }): { events: HubEvent[]; total: number } {
    return queryEvents(this.db, opts);
  }

  count(opts?: { type?: string; source?: string }): number {
    return countEvents(this.db, opts);
  }
}
