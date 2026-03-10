import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { successResponse, errorResponse } from "../../helpers/responses";
import { createHubEvent, listHubEvents } from "../../db/hub-events";
import { sseManager } from "../../services/sse";
import { findMatchingSubscriptions } from "../../db/event-subscriptions";

export async function handleEmitEvent(
  req: Request,
  config: Config,
  db: Database,
  bridge?: AgentBridge,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  let body: { type: string; payload?: Record<string, unknown>; characterId?: string | null; source?: string | null };
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  const eventType = typeof body.type === "string" ? body.type.trim() : "";
  if (!eventType) {
    return errorResponse(400, "Missing required field: type");
  }

  const event = createHubEvent(db, {
    eventType,
    payload: typeof body.payload === "object" && body.payload !== null && !Array.isArray(body.payload)
      ? body.payload
      : undefined,
    characterId: body.characterId ?? undefined,
    source: body.source ?? undefined,
  });

  sseManager.broadcast({
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

  let triggered: { subscriptionId: string; jobName: string; jobId: string; skill: string }[] = [];
  if (bridge?.isPluginConnected()) {
    const subs = findMatchingSubscriptions(db, event.event_type, event.character_id);
    for (const sub of subs) {
      const jobName = `${sub.job_name_prefix}-${event.id.slice(0, 8)}`;
      try {
        await bridge.sendRequest(
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
          traceId,
        );
        triggered.push({
          subscriptionId: sub.id,
          jobName,
          jobId: `Jobs/${jobName}`,
          skill: sub.job_skill,
        });
      } catch {
        // ignore individual subscription failures
      }
    }
  }

  return successResponse({ event, triggeredJobs: triggered }, 201);
}

export function handleListEvents(
  req: Request,
  config: Config,
  db: Database
): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const url = new URL(req.url);
  const eventType = url.searchParams.get("type") ?? undefined;
  const characterId = url.searchParams.get("characterId") ?? undefined;
  const rawLimit = parseInt(url.searchParams.get("limit") ?? "50", 10);
  const limit = Math.min(config.listLimitMax, Math.max(1, Number.isNaN(rawLimit) ? 50 : rawLimit));
  const since = url.searchParams.get("since") ?? undefined;

  const events = listHubEvents(db, { eventType, characterId, limit, since });
  return successResponse(events);
}
