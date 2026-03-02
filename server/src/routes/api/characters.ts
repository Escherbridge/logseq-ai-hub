import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { successResponse, errorResponse, notFoundResponse } from "../../helpers/responses";
import {
  createCharacter,
  getCharacter,
  getCharacterByName,
  listCharacters,
  updateCharacter,
  deleteCharacter,
} from "../../db/characters";

function resolveCharacter(db: Database, idOrName: string) {
  return getCharacter(db, idOrName) ?? getCharacterByName(db, idOrName);
}

export function handleListCharacters(req: Request, config: Config, db: Database): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();
  return successResponse(listCharacters(db));
}

export async function handleCreateCharacter(
  req: Request,
  config: Config,
  db: Database
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  if (!body.name || typeof body.name !== "string" || !body.name.trim()) {
    return errorResponse(400, "Missing required field: name");
  }

  try {
    const character = createCharacter(db, {
      name: body.name.trim(),
      persona: typeof body.persona === "string" ? body.persona : undefined,
      system_prompt: typeof body.system_prompt === "string" ? body.system_prompt : undefined,
      model: typeof body.model === "string" ? body.model : undefined,
      skills: Array.isArray(body.skills)
        ? body.skills.filter((s): s is string => typeof s === "string")
        : undefined,
      metadata:
        typeof body.metadata === "object" && body.metadata !== null && !Array.isArray(body.metadata)
          ? (body.metadata as Record<string, unknown>)
          : undefined,
    });
    return successResponse(character, 201);
  } catch (err: any) {
    if (err.message?.includes("UNIQUE")) {
      return errorResponse(409, `Character name "${body.name}" already exists`);
    }
    return errorResponse(500, err.message);
  }
}

export function handleGetCharacter(
  req: Request,
  config: Config,
  db: Database,
  params: Record<string, string>
): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const character = resolveCharacter(db, params.id);
  if (!character) return notFoundResponse("Character not found");
  return successResponse(character);
}

export async function handleUpdateCharacter(
  req: Request,
  config: Config,
  db: Database,
  params: Record<string, string>
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  const existing = resolveCharacter(db, params.id);
  if (!existing) return notFoundResponse("Character not found");

  try {
    const updated = updateCharacter(db, existing.id, {
      name: typeof body.name === "string" ? body.name : undefined,
      persona: typeof body.persona === "string" ? body.persona : body.persona === null ? null : undefined,
      system_prompt: typeof body.system_prompt === "string" ? body.system_prompt : body.system_prompt === null ? null : undefined,
      model: typeof body.model === "string" ? body.model : body.model === null ? null : undefined,
      skills: Array.isArray(body.skills)
        ? body.skills.filter((s): s is string => typeof s === "string")
        : undefined,
      metadata:
        typeof body.metadata === "object" && body.metadata !== null && !Array.isArray(body.metadata)
          ? (body.metadata as Record<string, unknown>)
          : undefined,
    });
    return successResponse(updated!);
  } catch (err: any) {
    if (err.message?.includes("UNIQUE")) {
      return errorResponse(409, `Character name "${body.name}" already exists`);
    }
    return errorResponse(500, err.message);
  }
}

export function handleDeleteCharacter(
  req: Request,
  config: Config,
  db: Database,
  params: Record<string, string>
): Response {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const existing = resolveCharacter(db, params.id);
  if (!existing) return notFoundResponse("Character not found");
  deleteCharacter(db, existing.id);
  return successResponse({ deleted: true, id: existing.id });
}
