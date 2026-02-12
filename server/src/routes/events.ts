import type { Config } from "../config";
import { sseManager } from "../services/sse";

export function handleSSE(req: Request, config: Config): Response {
  const url = new URL(req.url);
  const token = url.searchParams.get("token");

  // Auth via query param (EventSource doesn't support headers)
  if (token !== config.pluginApiToken) {
    return Response.json({ error: "Unauthorized" }, { status: 401 });
  }

  const clientId = crypto.randomUUID();

  const stream = new ReadableStream({
    start(controller) {
      sseManager.addClient(clientId, controller);

      // Send initial connected event
      const encoder = new TextEncoder();
      const connectMsg = `event: connected\ndata: ${JSON.stringify({
        type: "connected",
        timestamp: new Date().toISOString(),
        clientId,
      })}\n\n`;
      controller.enqueue(encoder.encode(connectMsg));
    },
    cancel() {
      sseManager.removeClient(clientId);
    },
  });

  return new Response(stream, {
    headers: {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      "Connection": "keep-alive",
      "Access-Control-Allow-Origin": "*",
    },
  });
}
