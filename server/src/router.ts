import type { Config } from "./config";
import type { Database } from "bun:sqlite";
import type { AgentBridge } from "./services/agent-bridge";
import type { ConversationStore } from "./services/conversations";
import type { ApprovalStore } from "./services/approval-store";
import { handleHealth } from "./routes/health";
import {
  handleWhatsAppVerify,
  handleWhatsAppWebhook,
} from "./routes/webhooks/whatsapp";
import { handleTelegramWebhook } from "./routes/webhooks/telegram";
import { handleSSE } from "./routes/events";
import { handleSendMessage } from "./routes/api/send";
import { handleGetMessages } from "./routes/api/messages";
import { handleAgentCallback } from "./routes/api/agent-callback";
import { handleCreateJob, handleListJobs, handleGetJob, handleStartJob, handleCancelJob, handlePauseJob, handleResumeJob } from "./routes/api/jobs";
import { handleListSkills, handleGetSkill, handleCreateSkill } from "./routes/api/skills";
import { handleListMCPServers, handleListMCPTools, handleListMCPResources } from "./routes/api/mcp";
import { handleAgentChat } from "./routes/api/agent-chat";
import { handleListSecretKeys, handleSetSecret, handleRemoveSecret, handleCheckSecret } from "./routes/api/secrets";
import { handleMcpRequest, handleMcpDelete, handleMcpConfig } from "./routes/mcp-transport";
import { handleListApprovals, handleResolveApproval, handleCancelApproval, handleAskApproval } from "./routes/api/approvals";
import { matchRoute } from "./router/match";

export interface RouteContext {
  config: Config;
  db: Database;
  agentBridge?: AgentBridge;
  conversations?: ConversationStore;
  approvalStore?: ApprovalStore;
  traceId?: string;
}

interface RouteEntry {
  method: string;
  pattern: string;
  handler: (
    req: Request,
    ctx: RouteContext,
    params: Record<string, string>
  ) => Response | Promise<Response>;
}

function corsHeaders(): HeadersInit {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
  };
}

export function createRouter(ctx: RouteContext) {
  const routes: RouteEntry[] = [
    {
      method: "GET",
      pattern: "/health",
      handler: (req, ctx) => handleHealth(req, ctx.config, ctx.agentBridge, ctx.traceId),
    },
    {
      method: "GET",
      pattern: "/webhook/whatsapp",
      handler: (req, ctx) => handleWhatsAppVerify(req, ctx.config),
    },
    {
      method: "POST",
      pattern: "/webhook/whatsapp",
      handler: (req, ctx) => handleWhatsAppWebhook(req, ctx.config, ctx.db, ctx.approvalStore),
    },
    {
      method: "POST",
      pattern: "/webhook/telegram",
      handler: (req, ctx) => handleTelegramWebhook(req, ctx.config, ctx.db, ctx.approvalStore),
    },
    {
      method: "GET",
      pattern: "/events",
      handler: (req, ctx) => handleSSE(req, ctx.config),
    },
    {
      method: "POST",
      pattern: "/api/send",
      handler: (req, ctx) => handleSendMessage(req, ctx.config, ctx.db),
    },
    {
      method: "GET",
      pattern: "/api/messages",
      handler: (req, ctx) => handleGetMessages(req, ctx.config, ctx.db),
    },
    {
      method: "POST",
      pattern: "/api/agent/callback",
      handler: (req, ctx) => {
        if (!ctx.agentBridge) {
          return Response.json({ success: false, error: "Agent bridge not initialized" }, { status: 503 });
        }
        return handleAgentCallback(req, ctx.config, ctx.agentBridge, ctx.traceId);
      },
    },
    // Job Management API
    {
      method: "POST",
      pattern: "/api/jobs",
      handler: (req, ctx) => handleCreateJob(req, ctx.config, ctx.agentBridge, ctx.traceId),
    },
    {
      method: "GET",
      pattern: "/api/jobs",
      handler: (req, ctx) => handleListJobs(req, ctx.config, ctx.agentBridge, ctx.traceId),
    },
    {
      method: "GET",
      pattern: "/api/jobs/:id",
      handler: (req, ctx, params) => handleGetJob(req, ctx.config, ctx.agentBridge, params, ctx.traceId),
    },
    {
      method: "PUT",
      pattern: "/api/jobs/:id/start",
      handler: (req, ctx, params) => handleStartJob(req, ctx.config, ctx.agentBridge, params, ctx.traceId),
    },
    {
      method: "PUT",
      pattern: "/api/jobs/:id/cancel",
      handler: (req, ctx, params) => handleCancelJob(req, ctx.config, ctx.agentBridge, params, ctx.traceId),
    },
    {
      method: "PUT",
      pattern: "/api/jobs/:id/pause",
      handler: (req, ctx, params) => handlePauseJob(req, ctx.config, ctx.agentBridge, params, ctx.traceId),
    },
    {
      method: "PUT",
      pattern: "/api/jobs/:id/resume",
      handler: (req, ctx, params) => handleResumeJob(req, ctx.config, ctx.agentBridge, params, ctx.traceId),
    },
    // Skills API
    {
      method: "GET",
      pattern: "/api/skills",
      handler: (req, ctx) => handleListSkills(req, ctx.config, ctx.agentBridge, ctx.traceId),
    },
    {
      method: "GET",
      pattern: "/api/skills/:id",
      handler: (req, ctx, params) => handleGetSkill(req, ctx.config, ctx.agentBridge, params, ctx.traceId),
    },
    {
      method: "POST",
      pattern: "/api/skills",
      handler: (req, ctx) => handleCreateSkill(req, ctx.config, ctx.agentBridge, ctx.traceId),
    },
    // MCP API
    {
      method: "GET",
      pattern: "/api/mcp/servers",
      handler: (req, ctx) => handleListMCPServers(req, ctx.config, ctx.agentBridge, ctx.traceId),
    },
    {
      method: "GET",
      pattern: "/api/mcp/servers/:id/tools",
      handler: (req, ctx, params) => handleListMCPTools(req, ctx.config, ctx.agentBridge, params, ctx.traceId),
    },
    {
      method: "GET",
      pattern: "/api/mcp/servers/:id/resources",
      handler: (req, ctx, params) => handleListMCPResources(req, ctx.config, ctx.agentBridge, params, ctx.traceId),
    },
    // Secrets API
    {
      method: "GET",
      pattern: "/api/secrets/keys",
      handler: (req, ctx) => handleListSecretKeys(req, ctx.config, ctx.agentBridge, ctx.traceId),
    },
    {
      method: "POST",
      pattern: "/api/secrets",
      handler: (req, ctx) => handleSetSecret(req, ctx.config, ctx.agentBridge, ctx.traceId),
    },
    {
      method: "DELETE",
      pattern: "/api/secrets/:key",
      handler: (req, ctx, params) => handleRemoveSecret(req, ctx.config, ctx.agentBridge, params, ctx.traceId),
    },
    {
      method: "GET",
      pattern: "/api/secrets/:key/check",
      handler: (req, ctx, params) => handleCheckSecret(req, ctx.config, ctx.agentBridge, params, ctx.traceId),
    },
    // Approvals API
    {
      method: "GET",
      pattern: "/api/approvals",
      handler: (req, ctx) => {
        if (!ctx.approvalStore) return Response.json({ success: false, error: "Approval store not initialized" }, { status: 503 });
        return handleListApprovals(req, ctx.config, ctx.approvalStore);
      },
    },
    {
      method: "POST",
      pattern: "/api/approvals/ask",
      handler: (req, ctx) => {
        if (!ctx.approvalStore) return Response.json({ success: false, error: "Approval store not initialized" }, { status: 503 });
        return handleAskApproval(req, ctx.config, ctx.approvalStore, ctx.db);
      },
    },
    {
      method: "POST",
      pattern: "/api/approvals/:id/resolve",
      handler: (req, ctx, params) => {
        if (!ctx.approvalStore) return Response.json({ success: false, error: "Approval store not initialized" }, { status: 503 });
        return handleResolveApproval(req, ctx.config, ctx.approvalStore, params);
      },
    },
    {
      method: "DELETE",
      pattern: "/api/approvals/:id",
      handler: (req, ctx, params) => {
        if (!ctx.approvalStore) return Response.json({ success: false, error: "Approval store not initialized" }, { status: 503 });
        return handleCancelApproval(req, ctx.config, ctx.approvalStore, params);
      },
    },
    // Agent Chat API
    {
      method: "POST",
      pattern: "/api/agent/chat",
      handler: (req, ctx) => {
        if (!ctx.conversations) {
          return Response.json({ success: false, error: "Agent not initialized" }, { status: 503 });
        }
        return handleAgentChat(req, ctx.config, ctx.agentBridge, ctx.conversations, ctx.traceId);
      },
    },
    // MCP Server Protocol (Streamable HTTP transport)
    {
      method: "POST",
      pattern: "/mcp",
      handler: (req, ctx) => handleMcpRequest(req, ctx),
    },
    {
      method: "GET",
      pattern: "/mcp",
      handler: (req, ctx) => handleMcpRequest(req, ctx),
    },
    {
      method: "DELETE",
      pattern: "/mcp",
      handler: (req, ctx) => handleMcpDelete(req, ctx),
    },
    {
      method: "GET",
      pattern: "/mcp/config",
      handler: (req, ctx) => handleMcpConfig(req, ctx.config),
    },
  ];

  return async (req: Request): Promise<Response> => {
    const url = new URL(req.url);
    const { pathname } = url;
    const method = req.method;

    const traceId = req.headers.get("X-Trace-Id") || crypto.randomUUID();

    // Handle CORS preflight
    if (method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: { ...corsHeaders(), "X-Trace-Id": traceId },
      });
    }

    // Route matching
    let matchedResponse: Response | null = null;
    for (const route of routes) {
      if (method !== route.method) continue;
      const params = matchRoute(route.pattern, pathname);
      if (params !== null) {
        const requestCtx: RouteContext = { ...ctx, traceId };
        matchedResponse = await route.handler(req, requestCtx, params);
        break;
      }
    }

    const response =
      matchedResponse ||
      Response.json(
        { success: false, error: "Not found" },
        { status: 404 }
      );

    // Add CORS headers and trace ID to all responses
    const headers = new Headers(response.headers);
    for (const [key, value] of Object.entries(corsHeaders())) {
      headers.set(key, value);
    }
    headers.set("X-Trace-Id", traceId);

    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers,
    });
  };
}
