import { describe, test, expect, mock } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerRegistryTools } from "../src/services/mcp/registry-tools";
import { createTestDb } from "./helpers";
import type { Config } from "../src/config";

const testConfig: Config = {
  port: 3000,
  whatsappVerifyToken: "",
  whatsappAccessToken: "",
  whatsappPhoneNumberId: "",
  telegramBotToken: "",
  pluginApiToken: "test-token",
  databasePath: ":memory:",
  llmApiKey: "",
  llmEndpoint: "",
  agentModel: "",
  agentRequestTimeout: 30000,
};

function createServer(): McpServer {
  return new McpServer(
    { name: "test-server", version: "0.0.1" },
    { capabilities: { tools: {}, resources: {}, prompts: {}, logging: {} } },
  );
}

function getRegisteredTools(server: McpServer): Record<string, any> {
  return (server as any)._registeredTools ?? {};
}

function createMockBridge(connected = true) {
  return {
    isPluginConnected: () => connected,
    sendRequest: mock(async (_op: string, _params: any, _traceId?: string) => ({
      entries: [],
      count: 0,
      version: 1,
    })),
    pendingCount: 0,
  };
}

// ──────────────────────────────────────────────────────────────────────────────
// Registration
// ──────────────────────────────────────────────────────────────────────────────

describe("registerRegistryTools", () => {
  test("registers exactly 4 tools", () => {
    const server = createServer();
    registerRegistryTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
    }));

    const tools = getRegisteredTools(server);
    const names = Object.keys(tools);
    expect(names).toContain("registry_list");
    expect(names).toContain("registry_search");
    expect(names).toContain("registry_get");
    expect(names).toContain("registry_refresh");
    expect(names.length).toBe(4);
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// Bridge interactions
// ──────────────────────────────────────────────────────────────────────────────

describe("registry tool handlers", () => {
  test("registry_list calls bridge with 'registry_list' operation", async () => {
    const server = createServer();
    const bridge = createMockBridge();
    registerRegistryTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    const tools = getRegisteredTools(server);
    await tools["registry_list"].handler({});

    expect(bridge.sendRequest).toHaveBeenCalledWith(
      "registry_list",
      {},
      undefined,
    );
  });

  test("registry_search calls bridge with query and optional type", async () => {
    const server = createServer();
    const bridge = createMockBridge();
    registerRegistryTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    const tools = getRegisteredTools(server);
    await tools["registry_search"].handler({
      query: "test",
      type: "tool",
    });

    expect(bridge.sendRequest).toHaveBeenCalledWith(
      "registry_search",
      { query: "test", type: "tool" },
      undefined,
    );
  });

  test("registry_get calls bridge with name and type", async () => {
    const server = createServer();
    const bridge = createMockBridge();
    registerRegistryTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    const tools = getRegisteredTools(server);
    await tools["registry_get"].handler({
      name: "my-tool",
      type: "tool",
    });

    expect(bridge.sendRequest).toHaveBeenCalledWith(
      "registry_get",
      { name: "my-tool", type: "tool" },
      undefined,
    );
  });

  test("registry_refresh calls bridge and triggers dynamic sync if available", async () => {
    const server = createServer();
    const bridge = createMockBridge();
    const syncFromBridge = mock(async () => ({
      tools: 0,
      prompts: 0,
      resources: 0,
    }));
    registerRegistryTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
      dynamicRegistry: { syncFromBridge } as any,
    }));

    const tools = getRegisteredTools(server);
    await tools["registry_refresh"].handler({});

    expect(bridge.sendRequest).toHaveBeenCalledWith(
      "registry_refresh",
      {},
      undefined,
    );
    expect(syncFromBridge).toHaveBeenCalled();
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// Error handling
// ──────────────────────────────────────────────────────────────────────────────

describe("registry tools error handling", () => {
  test("returns error when bridge is not connected", async () => {
    const server = createServer();
    const bridge = createMockBridge(false);
    registerRegistryTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    const tools = getRegisteredTools(server);
    const result = await tools["registry_list"].handler({});

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
  });

  test("returns error when bridge is absent", async () => {
    const server = createServer();
    registerRegistryTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
    }));

    const tools = getRegisteredTools(server);
    const result = await tools["registry_list"].handler({});

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
  });

  test("returns error when bridge sendRequest throws", async () => {
    const server = createServer();
    const bridge = {
      isPluginConnected: () => true,
      sendRequest: mock(async () => {
        throw new Error("Bridge timeout");
      }),
    };
    registerRegistryTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    const tools = getRegisteredTools(server);
    const result = await tools["registry_list"].handler({});

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("Bridge timeout");
  });
});
