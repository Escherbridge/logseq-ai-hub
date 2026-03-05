import { describe, test, expect, mock } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerTaskTools } from "../src/services/mcp/task-tools";
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

function createConnectedBridge(sendRequest: ReturnType<typeof mock>) {
  return {
    isPluginConnected: () => true,
    sendRequest,
    pendingCount: 0,
  } as any;
}

describe("registerTaskTools", () => {
  test("registers expected tool names", () => {
    const server = createServer();
    registerTaskTools(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);
    expect("track_create" in tools).toBe(true);
    expect("track_list" in tools).toBe(true);
    expect("track_update" in tools).toBe(true);
    expect("task_add" in tools).toBe(true);
    expect("task_update" in tools).toBe(true);
    expect("task_list" in tools).toBe(true);
    expect("project_dashboard" in tools).toBe(true);
  });

  test("track_create calls bridge.sendRequest with track_create operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ success: true, trackId: "feature-auth" }));
    const mockBridge = createConnectedBridge(sendRequest);
    registerTaskTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["track_create"].handler({
      project: "my-project",
      trackId: "feature-auth",
      description: "Implement authentication",
      type: "feature",
      priority: "high",
    });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("track_create");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({
      project: "my-project",
      trackId: "feature-auth",
      description: "Implement authentication",
    });
  });

  test("track_list calls bridge.sendRequest with filters", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ tracks: [], count: 0 }));
    const mockBridge = createConnectedBridge(sendRequest);
    registerTaskTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["track_list"].handler({ project: "my-project", status: "active", type: "feature" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("track_list");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({
      project: "my-project",
      status: "active",
      type: "feature",
    });
  });

  test("track_update calls bridge.sendRequest with track_update operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ success: true }));
    const mockBridge = createConnectedBridge(sendRequest);
    registerTaskTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["track_update"].handler({
      project: "my-project",
      trackId: "feature-auth",
      status: "completed",
      priority: "medium",
    });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("track_update");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({
      project: "my-project",
      trackId: "feature-auth",
      status: "completed",
    });
  });

  test("task_add calls bridge.sendRequest with task_add operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ success: true, taskIndex: 0 }));
    const mockBridge = createConnectedBridge(sendRequest);
    registerTaskTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["task_add"].handler({
      project: "my-project",
      trackId: "feature-auth",
      description: "Write unit tests for auth module",
      agent: "claude-code",
    });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("task_add");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({
      project: "my-project",
      trackId: "feature-auth",
      description: "Write unit tests for auth module",
    });
  });

  test("task_update calls bridge.sendRequest with task_update operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ success: true }));
    const mockBridge = createConnectedBridge(sendRequest);
    registerTaskTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["task_update"].handler({
      project: "my-project",
      trackId: "feature-auth",
      taskIndex: 0,
      status: "doing",
      agent: "claude-code",
    });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("task_update");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({
      project: "my-project",
      trackId: "feature-auth",
      taskIndex: 0,
      status: "doing",
    });
  });

  test("task_list calls bridge.sendRequest with task_list operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ tasks: [], count: 0 }));
    const mockBridge = createConnectedBridge(sendRequest);
    registerTaskTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["task_list"].handler({ project: "my-project", trackId: "feature-auth", status: "todo" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("task_list");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({
      project: "my-project",
      trackId: "feature-auth",
      status: "todo",
    });
  });

  test("project_dashboard calls bridge.sendRequest with project_dashboard operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ project: "my-project", tracks: [], summary: {} }));
    const mockBridge = createConnectedBridge(sendRequest);
    registerTaskTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["project_dashboard"].handler({ project: "my-project" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("project_dashboard");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ project: "my-project" });
  });

  test("returns error when bridge not connected", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({}));
    const mockBridge = {
      isPluginConnected: () => false,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerTaskTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["track_list"].handler({ project: "my-project" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
    expect(sendRequest.mock.calls.length).toBe(0);
  });

  test("returns error when bridge throws", async () => {
    const server = createServer();
    const sendRequest = mock(async () => { throw new Error("track not found"); });
    const mockBridge = createConnectedBridge(sendRequest);
    registerTaskTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["track_update"].handler({
      project: "my-project",
      trackId: "nonexistent-track",
      status: "completed",
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("track not found");
  });
});
