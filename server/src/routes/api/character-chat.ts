import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import { hasLlmCredentials } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { errorResponse, notFoundResponse } from "../../helpers/responses";
import { getCharacter, getCharacterByName } from "../../db/characters";
import { runCharacterTurn } from "../../services/character-runtime";

function resolveCharacter(db: Database, idOrName: string) {
  return getCharacter(db, idOrName) ?? getCharacterByName(db, idOrName);
}

export async function handleCharacterChat(
  req: Request,
  config: Config,
  db: Database,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  if (!hasLlmCredentials(config)) {
    return errorResponse(503, "LLM API key not configured");
  }

  const character = resolveCharacter(db, params.id);
  if (!character) return notFoundResponse("Character not found");

  let body: { message?: string; sessionId?: string };
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  if (!body.message || typeof body.message !== "string" || !body.message.trim()) {
    return errorResponse(400, "Missing required field: message");
  }

  try {
    const result = await runCharacterTurn(
      body.message.trim(),
      character,
      body.sessionId,
      config,
      db,
      bridge,
      traceId
    );
    return Response.json({ success: true, data: result });
  } catch (err: any) {
    if (err.name === "AbortError" || err.message?.includes("timeout")) {
      return errorResponse(504, "LLM request timed out");
    }
    return errorResponse(500, `Character chat error: ${err.message}`);
  }
}
