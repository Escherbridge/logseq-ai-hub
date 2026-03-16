import type { RouteContext } from "../../router";

// Rate limiting: per-source tracking
const rateLimitMap = new Map<string, { count: number; windowStart: number }>();
const RATE_LIMIT_MAX = 100;
const RATE_LIMIT_WINDOW_MS = 60_000;

// Global rate limiting
const GLOBAL_RATE_LIMIT_MAX = 500;
let globalRateLimit = { count: 0, windowStart: 0 };

const SOURCE_PATTERN = /^[a-z0-9-]+$/i;
const MAX_BODY_SIZE = 1_048_576; // 1MB

/**
 * POST /webhook/event/:source
 *
 * Accepts webhook payloads from external services, validates the source,
 * enforces rate limiting, and publishes a HubEvent via the EventBus.
 * No per-source token verification in v1.
 */
export async function handleEventWebhook(
  req: Request,
  ctx: RouteContext,
  params: Record<string, string>
): Promise<Response> {
  const source = params.source;

  // Validate source param
  if (!source || !SOURCE_PATTERN.test(source)) {
    return Response.json(
      { success: false, error: "Invalid source name. Must match /^[a-z0-9-]+$/i" },
      { status: 400 }
    );
  }

  // Check webhook secret if configured
  const configuredSecret = ctx.config.webhookSecret;
  if (configuredSecret) {
    const providedSecret = req.headers.get("X-Webhook-Secret");
    if (providedSecret !== configuredSecret) {
      return Response.json(
        { success: false, error: "Unauthorized" },
        { status: 401 }
      );
    }
  }

  // Rate limiting
  const now = Date.now();

  // Global rate limit
  if (now - globalRateLimit.windowStart > RATE_LIMIT_WINDOW_MS) {
    globalRateLimit = { count: 1, windowStart: now };
  } else {
    globalRateLimit.count++;
    if (globalRateLimit.count > GLOBAL_RATE_LIMIT_MAX) {
      return Response.json(
        { success: false, error: "Global rate limit exceeded" },
        { status: 429 }
      );
    }
  }

  // Per-source rate limit
  let entry = rateLimitMap.get(source);
  if (!entry || now - entry.windowStart > RATE_LIMIT_WINDOW_MS) {
    entry = { count: 0, windowStart: now };
    rateLimitMap.set(source, entry);
  }
  entry.count++;

  if (entry.count > RATE_LIMIT_MAX) {
    const retryAfter = Math.ceil(
      (entry.windowStart + RATE_LIMIT_WINDOW_MS - now) / 1000
    );
    return Response.json(
      { success: false, error: "Rate limit exceeded" },
      {
        status: 429,
        headers: { "Retry-After": String(Math.max(retryAfter, 1)) },
      }
    );
  }

  // Prune stale entries when map grows large
  if (rateLimitMap.size > 500) {
    for (const [key, entry] of rateLimitMap) {
      if (now - entry.windowStart > RATE_LIMIT_WINDOW_MS) {
        rateLimitMap.delete(key);
      }
    }
  }

  // Read and validate body size
  const rawBody = await req.text();
  if (rawBody.length > MAX_BODY_SIZE) {
    return Response.json(
      { success: false, error: `Payload too large (max ${MAX_BODY_SIZE} bytes)` },
      { status: 413 }
    );
  }

  // Parse JSON body
  let body: Record<string, unknown>;
  try {
    body = JSON.parse(rawBody);
  } catch {
    return Response.json(
      { success: false, error: "Invalid JSON body" },
      { status: 400 }
    );
  }

  // Publish HubEvent
  if (!ctx.eventBus) {
    return Response.json(
      { success: false, error: "Event bus not initialized" },
      { status: 503 }
    );
  }

  const event = ctx.eventBus.publish({
    type: "webhook.received",
    source: `webhook:${source}`,
    data: body,
    metadata: { severity: "info" },
  });

  return Response.json({ success: true, eventId: event.id });
}

/**
 * GET /webhook/event/:source
 *
 * Verification endpoint for webhook providers that require challenge-response.
 * Echoes the `hub.challenge` query param if present.
 */
export function handleEventWebhookVerify(
  req: Request,
  _ctx: RouteContext,
  _params: Record<string, string>
): Response {
  const url = new URL(req.url);
  const challenge = url.searchParams.get("hub.challenge");

  if (challenge) {
    return Response.json({ challenge });
  }

  return Response.json({ status: "ok" });
}

/**
 * Exported for testing: resets the rate limit map.
 */
export function _resetRateLimits(): void {
  rateLimitMap.clear();
  globalRateLimit = { count: 0, windowStart: 0 };
}
