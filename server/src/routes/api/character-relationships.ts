import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { errorResponse, notFoundResponse } from "../../helpers/responses";
import { getCharacter, getCharacterByName } from "../../db/characters";
import {
  setRelationship,
  getRelationship,
  listRelationships,
  deleteRelationship,
} from "../../db/character-relationships";

function resolveCharacter(db: Database, idOrName: string) {
  return getCharacter(db, idOrName) ?? getCharacterByName(db, idOrName);
}

export function handleListRelationships(
  req: Request,
  config: Config,
  db: Database,
  params: Record<string, string>
): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const character = resolveCharacter(db, params.id);
  if (!character) return notFoundResponse("Character not found");

  const relationships = listRelationships(db, character.id);
  return Response.json({ success: true, data: relationships });
}

export async function handleSetRelationship(
  req: Request,
  config: Config,
  db: Database,
  params: Record<string, string>
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const from = resolveCharacter(db, params.id);
  if (!from) return notFoundResponse("Source character not found");

  const to = resolveCharacter(db, params.targetId);
  if (!to) return notFoundResponse("Target character not found");

  if (from.id === to.id) {
    return errorResponse(400, "A character cannot have a relationship with itself");
  }

  let body: { type?: string; strength?: number; notes?: string | null };
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  if (!body.type || typeof body.type !== "string" || !body.type.trim()) {
    return errorResponse(400, "Missing required field: type");
  }

  if (body.strength !== undefined) {
    const s = Number(body.strength);
    if (!Number.isInteger(s) || s < -100 || s > 100) {
      return errorResponse(400, "strength must be an integer between -100 and 100");
    }
  }

  const rel = setRelationship(db, from.id, to.id, {
    type: body.type.trim(),
    strength: body.strength,
    notes: body.notes ?? null,
  });

  return Response.json({ success: true, data: rel });
}

export function handleGetRelationship(
  req: Request,
  config: Config,
  db: Database,
  params: Record<string, string>
): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const from = resolveCharacter(db, params.id);
  if (!from) return notFoundResponse("Source character not found");

  const to = resolveCharacter(db, params.targetId);
  if (!to) return notFoundResponse("Target character not found");

  const rel = getRelationship(db, from.id, to.id);
  if (!rel) return notFoundResponse("Relationship not found");

  return Response.json({ success: true, data: rel });
}

export function handleDeleteRelationship(
  req: Request,
  config: Config,
  db: Database,
  params: Record<string, string>
): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();

  const from = resolveCharacter(db, params.id);
  if (!from) return notFoundResponse("Source character not found");

  const to = resolveCharacter(db, params.targetId);
  if (!to) return notFoundResponse("Target character not found");

  const deleted = deleteRelationship(db, from.id, to.id);
  if (!deleted) return notFoundResponse("Relationship not found");

  return Response.json({ success: true, data: { deleted: true } });
}
