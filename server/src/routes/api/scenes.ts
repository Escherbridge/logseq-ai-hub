import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { errorResponse } from "../../helpers/responses";
import { getCharacter, getCharacterByName } from "../../db/characters";
import { runCharacterTurn } from "../../services/character-runtime";

function resolveCharacter(db: Database, idOrName: string) {
  return getCharacter(db, idOrName) ?? getCharacterByName(db, idOrName);
}

export interface SceneReaction {
  character: { id: string; name: string };
  sessionId: string;
  response: string;
  error?: string;
}

export async function handleRunScene(
  req: Request,
  config: Config,
  db: Database,
  bridge: AgentBridge | undefined,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  if (!config.llmApiKey) {
    return errorResponse(503, "LLM API key not configured");
  }

  let body: {
    description?: string;
    characterIds?: unknown;
    sessionIds?: unknown;
  };
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  if (!body.description || typeof body.description !== "string" || !body.description.trim()) {
    return errorResponse(400, "Missing required field: description");
  }

  if (!Array.isArray(body.characterIds) || body.characterIds.length === 0) {
    return errorResponse(400, "characterIds must be a non-empty array");
  }

  const sessionIds: Record<string, string> =
    body.sessionIds && typeof body.sessionIds === "object" && !Array.isArray(body.sessionIds)
      ? (body.sessionIds as Record<string, string>)
      : {};

  const description = body.description.trim();
  const MAX_PARTICIPANTS = 20;
  const ids = (body.characterIds as unknown[]).slice(0, MAX_PARTICIPANTS);

  const tasks = ids.map(async (idOrName): Promise<SceneReaction> => {
    if (typeof idOrName !== "string") {
      return {
        character: { id: String(idOrName), name: String(idOrName) },
        sessionId: "",
        response: "",
        error: "Invalid character identifier",
      };
    }

    const character = resolveCharacter(db, idOrName);
    if (!character) {
      return {
        character: { id: idOrName, name: idOrName },
        sessionId: "",
        response: "",
        error: `Character "${idOrName}" not found`,
      };
    }

    const sessionId =
      sessionIds[character.id] ?? sessionIds[character.name] ?? sessionIds[idOrName];

    try {
      const result = await runCharacterTurn(
        description,
        character,
        sessionId,
        config,
        db,
        bridge,
        traceId
      );
      return { character: result.character, sessionId: result.sessionId, response: result.response };
    } catch (err: any) {
      return {
        character: { id: character.id, name: character.name },
        sessionId: sessionId ?? "",
        response: "",
        error: err.message ?? "Unknown error",
      };
    }
  });

  const reactions = await Promise.allSettled(tasks);

  return Response.json({
    success: true,
    data: {
      description,
      reactions: reactions.map((r) =>
        r.status === "fulfilled" ? r.value : { character: { id: "", name: "" }, sessionId: "", response: "", error: String(r.reason) }
      ),
    },
  });
}
