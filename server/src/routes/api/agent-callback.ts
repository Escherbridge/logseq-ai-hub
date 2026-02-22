import type { Config } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import type { AgentCallback } from "../../types/agent";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { successResponse, errorResponse } from "../../helpers/responses";

export async function handleAgentCallback(
  req: Request,
  config: Config,
  bridge: AgentBridge,
  _traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) {
    return unauthorizedResponse();
  }

  let body: AgentCallback;
  try {
    body = (await req.json()) as AgentCallback;
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  if (!body.requestId) {
    return errorResponse(400, "Missing required field: requestId");
  }

  if (typeof body.success !== "boolean") {
    return errorResponse(400, "Missing required field: success");
  }

  const resolved = bridge.resolveRequest(body.requestId, body);
  if (!resolved) {
    return errorResponse(404, `No pending request found for ID: ${body.requestId}`);
  }

  return successResponse({ acknowledged: true });
}
