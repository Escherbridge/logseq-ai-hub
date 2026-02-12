import type { Config } from "./config";
import type { Database } from "bun:sqlite";
import { handleHealth } from "./routes/health";

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
