import type { Config } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { bridgeGuard, bridgeRequest } from "../../helpers/bridge";
import { errorResponse, successResponse } from "../../helpers/responses";
import { validateSecretSet } from "../../validation/secrets";

// Rate limiting state for secret_set
const setSecretCalls: number[] = [];
const RATE_LIMIT_WINDOW_MS = 60_000; // 1 minute
const RATE_LIMIT_MAX = 10;

function isRateLimited(): boolean {
  const now = Date.now();
  // Remove entries older than the window
  while (setSecretCalls.length > 0 && setSecretCalls[0]! < now - RATE_LIMIT_WINDOW_MS) {
    setSecretCalls.shift();
  }
  return setSecretCalls.length >= RATE_LIMIT_MAX;
}

function recordSetCall(): void {
  setSecretCalls.push(Date.now());
}

/**
 * GET /api/secrets/keys
 * List all secret key names (never values)
 */
export async function handleListSecretKeys(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const guard = bridgeGuard(bridge);
  if (guard) return guard;
  return bridgeRequest(bridge!, "list_secret_keys", {}, traceId);
}

/**
 * POST /api/secrets
 * Set a secret key-value pair
 * Body: { key: string, value: string }
 */
export async function handleSetSecret(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const guard = bridgeGuard(bridge);
  if (guard) return guard;

  // Rate limiting
  if (isRateLimited()) {
    return errorResponse(429, "Rate limit exceeded: max 10 secret_set calls per minute");
  }

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  const validation = validateSecretSet(body);
  if (!validation.valid) {
    return errorResponse(400, validation.error!);
  }

  recordSetCall();
  const { key, value } = body as { key: string; value: string };
  return bridgeRequest(bridge!, "set_secret", { key, value }, traceId);
}

/**
 * DELETE /api/secrets/:key
 * Remove a secret by key name
 */
export async function handleRemoveSecret(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const guard = bridgeGuard(bridge);
  if (guard) return guard;
  return bridgeRequest(bridge!, "remove_secret", { key: params.key }, traceId);
}

/**
 * GET /api/secrets/:key/check
 * Check if a secret key exists (never reveals value)
 */
export async function handleCheckSecret(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const guard = bridgeGuard(bridge);
  if (guard) return guard;

  try {
    const result = (await bridge!.sendRequest(
      "list_secret_keys",
      {},
      traceId
    )) as { keys: string[] };
    const exists = result.keys?.includes(params.key) ?? false;
    return successResponse({ exists, key: params.key });
  } catch (err: any) {
    return errorResponse(500, err.message || "Internal server error");
  }
}
