import { describe, test, expect, mock } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerPiDevTools } from "../src/services/mcp/pidev-tools";
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
  return (server as any)._registeredTools as Record<string, any>;
}

function createMockPiDevManager(enabled = true) {
  return {
    isEnabled: () => enabled,
    validateInstall: mock(async () => ({ valid: true, version: "1.0.0" })),
    spawn: mock(async (project: string, task: string) => ({
      id: "pi-1-123",
      project,
      task,
      status: "running",
      startedAt: new Date(),
      output: [],
    })),
    send: mock(async () => ({ received: true, output: "ok" })),
    status: mock((id: string) => ({
      id,
      status: "running",
      project: "test",
      task: "test",
      startedAt: new Date(),
      output: [],
    })),
    stop: mock(async () => ({ stopped: true, output: [] })),
    listSessions: mock(() => []),
  } as any;
}

describe("registerPiDevTools", () => {
  test("registers expected tool names", () => {
    const server = createServer();
    registerPiDevTools(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);
    expect("pi_spawn" in tools).toBe(true);
    expect("pi_send" in tools).toBe(true);
    expect("pi_status" in tools).toBe(true);
    expect("pi_stop" in tools).toBe(true);
    expect("pi_list_sessions" in tools).toBe(true);
    expect("pi_agent_create" in tools).toBe(true);
    expect("pi_agent_list" in tools).toBe(true);
    expect("pi_agent_get" in tools).toBe(true);
    expect("pi_agent_update" in tools).toBe(true);
  });

  test("pi_spawn calls manager.spawn", async () => {
    const server = createServer();
    const manager = createMockPiDevManager();
    registerPiDevTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      piDevManager: manager,
    }));

    const tools = getRegisteredTools(server);
    await tools["pi_spawn"].handler({ project: "test-proj", task: "implement feature" });

    expect(manager.spawn.mock.calls.length).toBe(1);
    expect(manager.spawn.mock.calls[0][0]).toBe("test-proj");
    expect(manager.spawn.mock.calls[0][1]).toBe("implement feature");
  });

  test("pi_spawn returns error when piDevManager not available", async () => {
    const server = createServer();
    registerPiDevTools(server, () => ({ db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["pi_spawn"].handler({ project: "test", task: "test" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not available");
  });

  test("pi_spawn returns error when pi.dev disabled", async () => {
    const server = createServer();
    const manager = createMockPiDevManager(false);
    registerPiDevTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      piDevManager: manager,
    }));

    const tools = getRegisteredTools(server);
    const result = await tools["pi_spawn"].handler({ project: "test", task: "test" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("disabled");
  });

  test("pi_send calls manager.send", async () => {
    const server = createServer();
    const manager = createMockPiDevManager();
    registerPiDevTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      piDevManager: manager,
    }));

    const tools = getRegisteredTools(server);
    await tools["pi_send"].handler({ sessionId: "pi-1-123", message: "hello" });

    expect(manager.send.mock.calls.length).toBe(1);
    expect(manager.send.mock.calls[0][0]).toBe("pi-1-123");
    expect(manager.send.mock.calls[0][1]).toBe("hello");
  });

  test("pi_status calls manager.status", async () => {
    const server = createServer();
    const manager = createMockPiDevManager();
    registerPiDevTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      piDevManager: manager,
    }));

    const tools = getRegisteredTools(server);
    const result = await tools["pi_status"].handler({ sessionId: "pi-1-123" });

    expect(manager.status.mock.calls.length).toBe(1);
    expect(result.isError).toBeUndefined();
  });

  test("pi_stop calls manager.stop", async () => {
    const server = createServer();
    const manager = createMockPiDevManager();
    registerPiDevTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      piDevManager: manager,
    }));

    const tools = getRegisteredTools(server);
    await tools["pi_stop"].handler({ sessionId: "pi-1-123" });

    expect(manager.stop.mock.calls.length).toBe(1);
  });

  test("pi_list_sessions calls manager.listSessions", async () => {
    const server = createServer();
    const manager = createMockPiDevManager();
    registerPiDevTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      piDevManager: manager,
    }));

    const tools = getRegisteredTools(server);
    await tools["pi_list_sessions"].handler({});

    expect(manager.listSessions.mock.calls.length).toBe(1);
  });

  test("pi_agent_list calls bridge.sendRequest", async () => {
    const server = createServer();
    const manager = createMockPiDevManager();
    const sendRequest = mock(async () => ({ agents: [], count: 0 }));
    const mockBridge = { isPluginConnected: () => true, sendRequest, pendingCount: 0 } as any;
    registerPiDevTools(server, () => ({
      bridge: mockBridge,
      db: createTestDb(),
      config: testConfig,
      piDevManager: manager,
    }));

    const tools = getRegisteredTools(server);
    await tools["pi_agent_list"].handler({});

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("pi_agent_list");
  });

  test("pi_agent_create calls bridge.sendRequest", async () => {
    const server = createServer();
    const manager = createMockPiDevManager();
    const sendRequest = mock(async () => ({ page: "PI-Agents/test", created: true }));
    const mockBridge = { isPluginConnected: () => true, sendRequest, pendingCount: 0 } as any;
    registerPiDevTools(server, () => ({
      bridge: mockBridge,
      db: createTestDb(),
      config: testConfig,
      piDevManager: manager,
    }));

    const tools = getRegisteredTools(server);
    await tools["pi_agent_create"].handler({ name: "test-agent", project: "test-proj" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("pi_agent_create");
  });

  test("pi_agent_get returns error when bridge not connected", async () => {
    const server = createServer();
    const manager = createMockPiDevManager();
    const mockBridge = { isPluginConnected: () => false, sendRequest: mock(async () => ({})), pendingCount: 0 } as any;
    registerPiDevTools(server, () => ({
      bridge: mockBridge,
      db: createTestDb(),
      config: testConfig,
      piDevManager: manager,
    }));

    const tools = getRegisteredTools(server);
    const result = await tools["pi_agent_get"].handler({ name: "test-agent" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
  });

  test("pi_agent_update calls bridge.sendRequest", async () => {
    const server = createServer();
    const manager = createMockPiDevManager();
    const sendRequest = mock(async () => ({ name: "test", updated: true }));
    const mockBridge = { isPluginConnected: () => true, sendRequest, pendingCount: 0 } as any;
    registerPiDevTools(server, () => ({
      bridge: mockBridge,
      db: createTestDb(),
      config: testConfig,
      piDevManager: manager,
    }));

    const tools = getRegisteredTools(server);
    await tools["pi_agent_update"].handler({ name: "test-agent", model: "new-model" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("pi_agent_update");
  });
});
