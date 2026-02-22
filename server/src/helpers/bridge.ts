import type { AgentBridge } from "../services/agent-bridge";
import { successResponse, errorResponse } from "./responses";

export function bridgeGuard(bridge: AgentBridge | undefined): Response | null {
  if (!bridge) return errorResponse(503, "Agent bridge not initialized");
  if (!bridge.isPluginConnected()) return errorResponse(503, "Plugin not connected");
  return null;
}

export async function bridgeRequest(
  bridge: AgentBridge,
  operation: string,
  params: Record<string, unknown>,
  traceId?: string
): Promise<Response> {
  try {
    const result = await bridge.sendRequest(operation, params, traceId);
    return successResponse(result);
  } catch (err: any) {
    if (err.message?.includes("timed out")) {
      return errorResponse(504, "Plugin did not respond in time");
    }
    if (err.message?.includes("not found") || err.message?.includes("Not found")) {
      return errorResponse(404, err.message);
    }
    if (err.message?.includes("conflict") || err.message?.includes("already")) {
      return errorResponse(409, err.message);
    }
    return errorResponse(500, err.message || "Internal server error");
  }
}
