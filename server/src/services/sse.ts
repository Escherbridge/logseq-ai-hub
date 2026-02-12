import type { SSEEvent } from "../types";

interface SSEClient {
  id: string;
  controller: ReadableStreamDefaultController;
}

class SSEManager {
  private clients: Map<string, SSEClient> = new Map();
  private eventId = 0;
  private heartbeatInterval: ReturnType<typeof setInterval> | null = null;

  start(): void {
    this.heartbeatInterval = setInterval(() => {
      this.broadcast({
        type: "heartbeat",
        data: { timestamp: new Date().toISOString() },
      });
    }, 30_000);
  }

  stop(): void {
    if (this.heartbeatInterval) {
      clearInterval(this.heartbeatInterval);
      this.heartbeatInterval = null;
    }
    this.clients.clear();
  }

  addClient(id: string, controller: ReadableStreamDefaultController): void {
    this.clients.set(id, { id, controller });
  }

  removeClient(id: string): void {
    this.clients.delete(id);
  }

  get clientCount(): number {
    return this.clients.size;
  }

  broadcast(event: SSEEvent): void {
    this.eventId++;
    const data = JSON.stringify({ type: event.type, ...event.data });
    const message = `event: ${event.type}\ndata: ${data}\nid: ${this.eventId}\n\n`;
    const encoder = new TextEncoder();
    const encoded = encoder.encode(message);

    for (const [id, client] of this.clients) {
      try {
        client.controller.enqueue(encoded);
      } catch {
        this.clients.delete(id);
      }
    }
  }
}

export const sseManager = new SSEManager();
