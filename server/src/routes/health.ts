import type { Config } from "../config";
import { sseManager } from "../services/sse";

const startTime = Date.now();

export function handleHealth(_req: Request, _config: Config): Response {
  return Response.json({
    status: "ok",
    uptime: Math.floor((Date.now() - startTime) / 1000),
    sseClients: sseManager.clientCount,
  });
}
