import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import { createHubEvent, listHubEvents } from "../../db/hub-events";
import {
  createEventSubscription,
  getEventSubscription,
  listEventSubscriptions,
  deleteEventSubscription,
  findMatchingSubscriptions,
} from "../../db/event-subscriptions";

const DEFAULT_LIST_LIMIT = 50;

function err(text: string) {
  return { content: [{ type: "text" as const, text }], isError: true as const };
}

function ok(data: unknown) {
  return { content: [{ type: "text" as const, text: JSON.stringify(data, null, 2) }] };
}

function clampLimit(limit: number | undefined, max: number): number {
  if (limit === undefined || limit === null) return Math.min(DEFAULT_LIST_LIMIT, max);
  const n = Number(limit);
  if (!Number.isFinite(n) || n < 1) return Math.min(DEFAULT_LIST_LIMIT, max);
  return Math.min(n, max);
}

export function registerEventTools(server: McpServer, getContext: () => McpToolContext): void {
  server.tool(
    "hub_event_emit",
    "Emit a hub event (persisted and broadcast over SSE). Optional characterId and source.",
    {
      type: z.string().describe("Event type identifier"),
      payload: z.record(z.unknown()).optional().describe("Event payload object"),
      characterId: z.string().nullable().optional(),
      source: z.string().nullable().optional(),
    },
    async ({ type, payload, characterId, source }) => {
      const ctx = getContext();
      const eventType = typeof type === "string" ? type.trim() : "";
      if (!eventType) return err("Missing or empty event type");
      try {
        const event = createHubEvent(ctx.db, {
          eventType,
          payload: payload ?? undefined,
          characterId: characterId ?? undefined,
          source: source ?? undefined,
        });
        ctx.sseManager?.broadcast({
          type: "hub_event",
          data: {
            id: event.id,
            eventType: event.event_type,
            payload: event.payload,
            characterId: event.character_id,
            source: event.source,
            createdAt: event.created_at,
          },
        });
        const triggered: { subscriptionId: string; jobName: string; jobId: string; skill: string }[] = [];
        if (ctx.bridge?.isPluginConnected()) {
          const subs = findMatchingSubscriptions(ctx.db, event.event_type, event.character_id);
          const prefix = event.id.slice(0, 8);
          for (const sub of subs) {
            const jobName = `${sub.job_name_prefix}-${prefix}`;
            try {
              await ctx.bridge.sendRequest(
                "create_job",
                {
                  name: jobName,
                  type: "event-driven",
                  priority: sub.priority,
                  skill: sub.job_skill,
                  input: {
                    event: {
                      id: event.id,
                      type: event.event_type,
                      payload: event.payload,
                      characterId: event.character_id,
                      source: event.source,
                      createdAt: event.created_at,
                    },
                  },
                },
                ctx.traceId
              );
              triggered.push({ subscriptionId: sub.id, jobName, jobId: `Jobs/${jobName}`, skill: sub.job_skill });
            } catch {
              // skip failed subscription
            }
          }
        }
        return ok({ event, triggeredJobs: triggered });
      } catch (e: unknown) {
        const message = e instanceof Error ? e.message : String(e);
        return err(`Error: ${message}`);
      }
    }
  );

  server.tool(
    "hub_event_list",
    "List hub events with optional filters. Limit is clamped to server config.",
    {
      type: z.string().optional().describe("Filter by event type"),
      characterId: z.string().optional(),
      limit: z.number().optional().describe("Max number of events to return"),
      since: z.string().optional().describe("Only events after this ISO timestamp"),
    },
    async ({ type, characterId, limit, since }) => {
      const ctx = getContext();
      const maxLimit = ctx.config.listLimitMax;
      const clamped = clampLimit(limit, maxLimit);
      const events = listHubEvents(ctx.db, { eventType: type, characterId, limit: clamped, since });
      return ok(events);
    }
  );

  server.tool(
    "event_subscription_list",
    "List event subscriptions with optional filters.",
    {
      eventType: z.string().optional(),
      characterId: z.string().optional(),
      enabled: z.boolean().optional(),
    },
    async ({ eventType, characterId, enabled }) => {
      const ctx = getContext();
      const subs = listEventSubscriptions(ctx.db, { eventType, characterId, enabled });
      return ok(subs);
    }
  );

  server.tool(
    "event_subscription_create",
    "Create an event subscription. When a matching hub event is emitted, a job is created.",
    {
      eventType: z.string().describe("Hub event type to match"),
      jobSkill: z.string().describe("Skill page name to run (e.g. Skills/my-skill)"),
      jobNamePrefix: z.string().describe("Prefix for the created job name"),
      characterId: z.string().nullable().optional().describe("Match only events for this character; null = any"),
      priority: z.number().min(1).max(5).optional(),
      enabled: z.boolean().optional(),
    },
    async (params) => {
      const ctx = getContext();
      const eventType = typeof params.eventType === "string" ? params.eventType.trim() : "";
      const jobSkill = typeof params.jobSkill === "string" ? params.jobSkill.trim() : "";
      const jobNamePrefix = typeof params.jobNamePrefix === "string" ? params.jobNamePrefix.trim() : "";
      if (!eventType) return err("Missing or empty eventType");
      if (!jobSkill) return err("Missing or empty jobSkill");
      if (!jobNamePrefix) return err("Missing or empty jobNamePrefix");
      try {
        const sub = createEventSubscription(ctx.db, {
          eventType,
          characterId: params.characterId ?? undefined,
          jobSkill,
          jobNamePrefix,
          priority: params.priority,
          enabled: params.enabled,
        });
        return ok(sub);
      } catch (e: unknown) {
        const message = e instanceof Error ? e.message : String(e);
        return err(`Error: ${message}`);
      }
    }
  );

  server.tool(
    "event_subscription_get",
    "Get an event subscription by ID.",
    { id: z.string().describe("Subscription ID") },
    async ({ id }) => {
      const ctx = getContext();
      const sub = getEventSubscription(ctx.db, id);
      return sub ? ok(sub) : err(`Event subscription "${id}" not found`);
    }
  );

  server.tool(
    "event_subscription_delete",
    "Delete an event subscription by ID.",
    { id: z.string().describe("Subscription ID") },
    async ({ id }) => {
      const ctx = getContext();
      const existing = getEventSubscription(ctx.db, id);
      if (!existing) return err(`Event subscription "${id}" not found`);
      deleteEventSubscription(ctx.db, id);
      return ok({ deleted: true, id });
    }
  );
}
