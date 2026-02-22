import { describe, test, expect } from "bun:test";
import { AgentBridge } from "../src/services/agent-bridge";

describe("AgentBridge", () => {
  test("starts with zero pending requests", () => {
    const bridge = new AgentBridge(1000);
    expect(bridge.pendingCount).toBe(0);
  });

  test("hasPendingRequest returns false initially", () => {
    const bridge = new AgentBridge(1000);
    expect(bridge.hasPendingRequest("nonexistent")).toBe(false);
  });

  test("isPluginConnected returns false when no SSE clients", () => {
    const bridge = new AgentBridge(1000);
    expect(bridge.isPluginConnected()).toBe(false);
  });

  test("sendRequest rejects when plugin not connected", async () => {
    const bridge = new AgentBridge(1000);
    try {
      await bridge.sendRequest("test_op", {});
      expect(true).toBe(false); // Should not reach here
    } catch (err: any) {
      expect(err.message).toContain("Plugin not connected");
    }
  });

  test("resolveRequest returns false for unknown requestId", () => {
    const bridge = new AgentBridge(1000);
    const resolved = bridge.resolveRequest("unknown-id", { requestId: "unknown-id", success: true, data: {} });
    expect(resolved).toBe(false);
  });
});
