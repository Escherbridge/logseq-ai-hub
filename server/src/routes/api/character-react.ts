import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { errorResponse, notFoundResponse } from "../../helpers/responses";
import { getCharacter, getCharacterByName } from "../../db/characters";
import { runCharacterTurn } from "../../services/character-runtime";

function resolveCharacter(db: Database, idOrName: string) {
  return getCharacter(db, idOrName) ?? getCharacterByName(db, idOrName);
}

function buildEventMessage(
  eventType: string,
  payload: Record<string, unknown> | undefined,
  source: string | undefined
): string {
  const sourceLine = source ? `\nSource: ${source}` : "";
  const payloadText = payload ? JSON.stringify(payload, null, 2) : "(no payload)";
  return `[Event: ${eventType}]${sourceLine}\n${payloadText}`;
}

export async function handleCharacterReact(
  req: Request,
  config: Config,
  db: Database,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  if (!config.llmApiKey) {
    return errorResponse(503, "LLM API key not configured");
  }

  const character = resolveCharacter(db, params.id);
  if (!character) return notFoundResponse("Character not found");

  let body: {
    eventType?: string;
    payload?: Record<string, unknown>;
    source?: string;
    sessionId?: string;
  };
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  if (!body.eventType || typeof body.eventType !== "string" || !body.eventType.trim()) {
    return errorResponse(400, "Missing required field: eventType");
  }

  const message = buildEventMessage(body.eventType.trim(), body.payload, body.source);

  try {
    const result = await runCharacterTurn(
      message,
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
    return errorResponse(500, `Character react error: ${err.message}`);
  }
}
