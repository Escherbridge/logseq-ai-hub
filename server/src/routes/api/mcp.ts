import type { Config } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { bridgeGuard, bridgeRequest } from "../../helpers/bridge";

export async function handleListMCPServers(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const guard = bridgeGuard(bridge);
  if (guard) return guard;
  return bridgeRequest(bridge!, "list_mcp_servers", {}, traceId);
}

export async function handleListMCPTools(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const guard = bridgeGuard(bridge);
  if (guard) return guard;
  return bridgeRequest(bridge!, "list_mcp_tools", { serverId: params.id }, traceId);
}

export async function handleListMCPResources(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();
  const guard = bridgeGuard(bridge);
  if (guard) return guard;
  return bridgeRequest(bridge!, "list_mcp_resources", { serverId: params.id }, traceId);
}
