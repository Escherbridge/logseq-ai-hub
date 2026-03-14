import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { successResponse, errorResponse, notFoundResponse } from "../../helpers/responses";
import { getCharacter, getCharacterByName } from "../../db/characters";
import {
  listCharacterSessions,
  getCharacterSession,
  deleteCharacterSession,
} from "../../db/character-sessions";

function resolveCharacter(db: Database, idOrName: string) {
  return getCharacter(db, idOrName) ?? getCharacterByName(db, idOrName);
}

export function handleListCharacterSessions(
  req: Request,
  config: Config,
  db: Database,
  params: Record<string, string>,
): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const character = resolveCharacter(db, params.id);
  if (!character) return notFoundResponse("Character not found");

  const sessions = listCharacterSessions(db, character.id).map((s) => {
    const last =
      s.messages.length > 0 ? s.messages[s.messages.length - 1] : null;
    return {
      id: s.id,
      characterId: s.character_id,
      createdAt: s.created_at,
      updatedAt: s.updated_at,
      lastRole: last?.role ?? null,
      lastContent:
        typeof last?.content === "string"
          ? last.content.slice(0, 200)
          : null,
    };
  });

  return successResponse(sessions);
}

export function handleGetCharacterSession(
  req: Request,
  config: Config,
  db: Database,
  params: Record<string, string>,
): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const session = getCharacterSession(db, params.id);
  if (!session) return notFoundResponse("Character session not found");
  return successResponse(session);
}

export function handleDeleteCharacterSession(
  req: Request,
  config: Config,
  db: Database,
  params: Record<string, string>,
): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const existing = getCharacterSession(db, params.id);
  if (!existing) return notFoundResponse("Character session not found");

  deleteCharacterSession(db, params.id);
  return successResponse({ deleted: true, id: params.id });
}

