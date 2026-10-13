import type { SSEEvent } from "../types";

interface SSEClient {
  id: string;
  controller: ReadableStreamDefaultController;
  /** Plugin build stamp sent on connect (scripts/stamp-build.js), or null for older plugins. */
  build: string | null;
}

/** Seconds between SSE heartbeats. Bun.serve's idleTimeout is derived from this in src/index.ts. */
export const HEARTBEAT_SECONDS = 15;

/**
 * Bun.serve's idleTimeout for a given LLM relay timeout: at least 10s above the relay's own
 * timeout (so the relay always gets to answer with a clean relay_timeout before Bun drops the
 * socket out from under it) and at least HEARTBEAT_SECONDS * 8 (so SSE streams never look
 * idle between heartbeats), capped at Bun's hard maximum of 255s.
 */
export function idleTimeoutSeconds(relayTimeoutSeconds: number): number {
  return Math.min(255, Math.max(HEARTBEAT_SECONDS * 8, relayTimeoutSeconds + 10));
}

export class SSEManager {
  private clients: Map<string, SSEClient> = new Map();
  private eventId = 0;
  private heartbeatInterval: ReturnType<typeof setInterval> | null = null;

  /** Keeps SSE streams from looking idle to Bun and to any proxy in front of it. */
  start(): void {
    this.heartbeatInterval = setInterval(() => {
      this.broadcast({
        type: "heartbeat",
        data: { timestamp: new Date().toISOString() },
      });
    }, HEARTBEAT_SECONDS * 1000);
  }

  stop(): void {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }
    this.clients.clear();
  }

  addClient(id: string, controller: ReadableStreamDefaultController, build: string | null = null): void {
    this.clients.set(id, { id, controller, build });
  }

  /** Build stamps of the connected plugins, newest connection last. */
  get builds(): (string | null)[] {
    return [...this.clients.values()].map((c) => c.build);
  }

  removeClient(id: string): void {
    this.clients.delete(id);
  }

  get clientCount(): number {
    return this.clients.size;
  }

  broadcast(event: SSEEvent): void {
    this.eventId++;
    const data = JSON.stringify({ type: event.type, ...event.data });
    const message = `event: ${event.type}\ndata: ${data}\nid: ${this.eventId}\n\n`;
    const encoder = new TextEncoder();
    const encoded = encoder.encode(message);

    for (const [id, client] of this.clients) {
      try {
        client.controller.enqueue(encoded);
      } catch {
        this.clients.delete(id);
      }
    }
  }
}

export const sseManager = new SSEManager();
