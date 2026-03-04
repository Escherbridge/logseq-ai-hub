import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { successResponse, errorResponse } from "../../helpers/responses";
import { createHubEvent, listHubEvents } from "../../db/hub-events";
import { sseManager } from "../../services/sse";

export async function handleEmitEvent(
  req: Request,
  config: Config,
  db: Database
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

  return successResponse(event, 201);
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
  const limit = Math.min(100, Math.max(1, parseInt(url.searchParams.get("limit") ?? "50", 10)));
  const since = url.searchParams.get("since") ?? undefined;

  const events = listHubEvents(db, { eventType, characterId, limit, since });
  return successResponse(events);
}
