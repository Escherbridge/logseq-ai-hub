import type { Config } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { successResponse, errorResponse } from "../../helpers/responses";
import { bridgeGuard, bridgeRequest } from "../../helpers/bridge";
import { validateSkillCreate } from "../../validation/skills";

export async function handleListSkills(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const guard = bridgeGuard(bridge);
  if (guard) return guard;
  return bridgeRequest(bridge!, "list_skills", {}, traceId);
}

export async function handleGetSkill(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const guard = bridgeGuard(bridge);
  if (guard) return guard;
  return bridgeRequest(bridge!, "get_skill", { skillId: params.id }, traceId);
}

export async function handleCreateSkill(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const guard = bridgeGuard(bridge);
  if (guard) return guard;

  let body: unknown;
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  const validation = validateSkillCreate(body);
  if (!validation.valid) {
    return errorResponse(400, validation.errors.join("; "));
  }

  try {
    const result = await bridge!.sendRequest("create_skill", validation.data as unknown as Record<string, unknown>, traceId);
    return successResponse(result, 201);
  } catch (err: any) {
    if (err.message?.includes("timed out")) {
      return errorResponse(504, "Plugin did not respond in time");
    }
    return errorResponse(500, err.message || "Failed to create skill");
  }
}
