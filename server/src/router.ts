import type { Config } from "./config";
import type { Database } from "bun:sqlite";
import { handleHealth } from "./routes/health";
import {
  handleWhatsAppVerify,
  handleWhatsAppWebhook,
} from "./routes/webhooks/whatsapp";
import { handleTelegramWebhook } from "./routes/webhooks/telegram";
import { handleSSE } from "./routes/events";
import { handleSendMessage } from "./routes/api/send";
import { handleGetMessages } from "./routes/api/messages";

export interface RouteContext {
  config: Config;
  db: Database;
}

function corsHeaders(): HeadersInit {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  };
}

export function createRouter(ctx: RouteContext) {
  return async (req: Request): Promise<Response> => {
    const url = new URL(req.url);
    const { pathname } = url;
    const method = req.method;

    // Handle CORS preflight
    if (method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    let response: Response;

    // Route matching
    if (method === "GET" && pathname === "/health") {
      response = handleHealth(req, ctx.config);
    } else if (method === "GET" && pathname === "/webhook/whatsapp") {
      response = handleWhatsAppVerify(req, ctx.config);
    } else if (method === "POST" && pathname === "/webhook/whatsapp") {
      response = await handleWhatsAppWebhook(req, ctx.config, ctx.db);
    } else if (method === "POST" && pathname === "/webhook/telegram") {
      response = await handleTelegramWebhook(req, ctx.config, ctx.db);
    } else if (method === "GET" && pathname === "/events") {
      response = handleSSE(req, ctx.config);
    } else if (method === "POST" && pathname === "/api/send") {
      response = await handleSendMessage(req, ctx.config, ctx.db);
    } else if (method === "GET" && pathname === "/api/messages") {
      response = handleGetMessages(req, ctx.config, ctx.db);
    } else {
      response = Response.json({ error: "Not found" }, { status: 404 });
    }

    // Add CORS headers to all responses
    const headers = new Headers(response.headers);
    for (const [key, value] of Object.entries(corsHeaders())) {
      headers.set(key, value);
    }

    return new Response(response.body, {
      status: response.status,
      statusText: response.statusText,
      headers,
    });
  };
}
