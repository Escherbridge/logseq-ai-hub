import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { successResponse, errorResponse, notFoundResponse } from "../../helpers/responses";
import {
  createEventSubscription,
  deleteEventSubscription,
  getEventSubscription,
  listEventSubscriptions,
} from "../../db/event-subscriptions";

export function handleListEventSubscriptions(req: Request, config: Config, db: Database): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const url = new URL(req.url);
  const eventType = url.searchParams.get("eventType") ?? undefined;
  const characterId = url.searchParams.get("characterId") ?? undefined;
  const enabledStr = url.searchParams.get("enabled");
  const enabled =
    enabledStr === null
      ? undefined
      : enabledStr === "true"
        ? true
        : enabledStr === "false"
          ? false
          : undefined;

  const subs = listEventSubscriptions(db, { eventType, characterId, enabled });
  return successResponse(subs);
}

export async function handleCreateEventSubscription(
  req: Request,
  config: Config,
  db: Database,
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  const eventType = typeof body.eventType === "string" ? body.eventType.trim() : "";
  const jobSkill = typeof body.jobSkill === "string" ? body.jobSkill.trim() : "";
  const jobNamePrefix = typeof body.jobNamePrefix === "string" ? body.jobNamePrefix.trim() : "";

  if (!eventType) return errorResponse(400, "Missing required field: eventType");
  if (!jobSkill) return errorResponse(400, "Missing required field: jobSkill");
  if (!jobNamePrefix) return errorResponse(400, "Missing required field: jobNamePrefix");

  const priority =
    body.priority === undefined
      ? undefined
      : typeof body.priority === "number"
        ? body.priority
        : undefined;

  if (priority !== undefined && (priority < 1 || priority > 5)) {
    return errorResponse(400, "priority must be between 1 and 5");
  }

  const characterId =
    body.characterId === undefined
      ? undefined
      : body.characterId === null
        ? null
        : typeof body.characterId === "string"
          ? body.characterId
          : undefined;

  const enabled =
    body.enabled === undefined ? undefined : body.enabled === false ? false : true;

  const sub = createEventSubscription(db, {
    eventType,
    characterId,
    jobSkill,
    jobNamePrefix,
    priority,
    enabled,
  });

  return successResponse(sub, 201);
}

export function handleGetEventSubscription(
  req: Request,
  config: Config,
  db: Database,
  params: Record<string, string>,
): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const sub = getEventSubscription(db, params.id);
  if (!sub) return notFoundResponse("Event subscription not found");
  return successResponse(sub);
}

export function handleDeleteEventSubscription(
  req: Request,
  config: Config,
  db: Database,
  params: Record<string, string>,
): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const existing = getEventSubscription(db, params.id);
  if (!existing) return notFoundResponse("Event subscription not found");
  deleteEventSubscription(db, params.id);
  return successResponse({ deleted: true, id: params.id });
}

