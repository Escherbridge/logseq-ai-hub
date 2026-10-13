import { timingSafeEqual } from "node:crypto";
import type { Config } from "../config";

/** Constant-time comparison; unequal lengths short-circuit (safe — length isn't the secret). */
function timingSafeEqualStrings(a: string, b: string): boolean {
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  if (bufA.length !== bufB.length) return false;
  return timingSafeEqual(bufA, bufB);
}

export function authenticate(req: Request, config: Config): boolean {
  const auth = req.headers.get("Authorization");
  if (!auth) return false;
  return timingSafeEqualStrings(auth, `Bearer ${config.pluginApiToken}`);
}

/**
 * Relay-only auth for /api/llm/*: the plugin token OR any configured relay token. Relay
 * tokens must never be accepted by `authenticate` — they unlock the LLM gateway only, not
 * /mcp or /api/send or the agent bridge broadcast surface.
 */
export function authenticateRelay(req: Request, config: Config): boolean {
  const auth = req.headers.get("Authorization");
  if (!auth) return false;
  if (timingSafeEqualStrings(auth, `Bearer ${config.pluginApiToken}`)) return true;
  return config.llmRelayTokens.some((token) => timingSafeEqualStrings(auth, `Bearer ${token}`));
}

export function unauthorizedResponse(): Response {
  return Response.json(
    { success: false, error: "Unauthorized" },
    { status: 401 }
  );
}
