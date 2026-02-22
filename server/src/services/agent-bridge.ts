import { sseManager } from "./sse";
import type { AgentCallback, PendingRequest } from "../types/agent";

export class AgentBridge {
  private pendingRequests: Map<string, PendingRequest> = new Map();
  private timeout: number;

  constructor(timeout = 30000) {
    this.timeout = timeout;
  }

  get pendingCount(): number {
    return this.pendingRequests.size;
  }

  hasPendingRequest(requestId: string): boolean {
    return this.pendingRequests.has(requestId);
  }

  isPluginConnected(): boolean {
    return sseManager.clientCount > 0;
  }

  sendRequest(operation: string, params: Record<string, unknown> = {}, traceId?: string): Promise<unknown> {
    if (!this.isPluginConnected()) {
      return Promise.reject(new Error("Plugin not connected"));
    }

    const requestId = crypto.randomUUID();

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pendingRequests.delete(requestId);
        reject(new Error(`Bridge request timed out after ${this.timeout}ms (operation: ${operation})`));
      }, this.timeout);

      const pending: PendingRequest = {
        requestId,
        operation,
        resolve,
        reject,
        timer,
        createdAt: Date.now(),
        traceId,
      };

      this.pendingRequests.set(requestId, pending);

      // Broadcast SSE event to plugin
      sseManager.broadcast({
        type: "agent_request",
        data: { requestId, operation, params, traceId },
      });
    });
  }

  getOperation(requestId: string): string | null {
    const pending = this.pendingRequests.get(requestId);
    return pending?.operation ?? null;
  }

  resolveRequest(requestId: string, result: AgentCallback): boolean {
    const pending = this.pendingRequests.get(requestId);
    if (!pending) return false;

    clearTimeout(pending.timer);
    this.pendingRequests.delete(requestId);

    if (result.success) {
      pending.resolve(result.data);
    } else {
      pending.reject(new Error(result.error || "Bridge request failed"));
    }

    return true;
  }
}
