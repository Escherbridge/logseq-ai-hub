import type { RouteContext } from "../../router";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";

export async function handlePublishEvent(
  req: Request,
  ctx: RouteContext
): Promise<Response> {
  if (!authenticate(req, ctx.config)) return unauthorizedResponse();

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return Response.json(
      { success: false, error: "Invalid JSON body" },
      { status: 400 }
    );
  }

  const { type, source, data, metadata } = body as Record<string, unknown>;

  if (!type || typeof type !== "string" || type.trim() === "") {
    return Response.json(
      { success: false, error: "type is required and must be a non-empty string" },
      { status: 400 }
    );
  }

  if (!source || typeof source !== "string" || source.trim() === "") {
    return Response.json(
      { success: false, error: "source is required and must be a non-empty string" },
      { status: 400 }
    );
  }

  if (!data || typeof data !== "object" || Array.isArray(data)) {
    return Response.json(
      { success: false, error: "data is required and must be an object" },
      { status: 400 }
    );
  }

  if (!ctx.eventBus) {
    return Response.json(
      { success: false, error: "EventBus not initialized" },
      { status: 503 }
    );
  }

  const event = ctx.eventBus.publish({
    type: type as string,
    source: source as string,
    data: data as Record<string, unknown>,
    ...(metadata ? { metadata: metadata as Record<string, unknown> } : {}),
  });

  return Response.json({ success: true, eventId: event.id });
}

export async function handleQueryEvents(
  req: Request,
  ctx: RouteContext
): Promise<Response> {
  if (!authenticate(req, ctx.config)) return unauthorizedResponse();

  if (!ctx.eventBus) {
    return Response.json(
      { success: false, error: "EventBus not initialized" },
      { status: 503 }
    );
  }

  const url = new URL(req.url);
  const type = url.searchParams.get("type") || undefined;
  const source = url.searchParams.get("source") || undefined;
  const since = url.searchParams.get("since") || undefined;
  const rawLimit = parseInt(url.searchParams.get("limit") || "50", 10);
  const limit = Math.min(Math.max(rawLimit, 1), 200);
  const rawOffset = parseInt(url.searchParams.get("offset") || "0", 10);
  const offset = Math.max(isNaN(rawOffset) ? 0 : rawOffset, 0);

  const { events, total } = ctx.eventBus.query({
    type,
    source,
    since,
    limit,
    offset,
  });

  return Response.json({ success: true, events, total });
}
