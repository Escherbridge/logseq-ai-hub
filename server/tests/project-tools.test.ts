import { describe, test, expect, mock } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerProjectTools } from "../src/services/mcp/project-tools";
import type { Config } from "../src/config";
import { createTestDb } from "./helpers";

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

describe("registerProjectTools", () => {
  test("registers expected tool names", () => {
    const server = createServer();
    registerProjectTools(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);
    expect("project_list" in tools).toBe(true);
    expect("project_get" in tools).toBe(true);
  });

  test("project_list calls bridge.sendRequest with 'project_list' operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ projects: [], count: 0 }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerProjectTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["project_list"].handler({});

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("project_list");
  });

  test("project_list passes status filter to bridge", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ projects: [], count: 0 }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerProjectTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["project_list"].handler({ status: "active" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("project_list");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ status: "active" });
  });

  test("project_get calls bridge.sendRequest with 'project_get' operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({
      name: "logseq-ai-hub",
      repo: "https://github.com/example/logseq-ai-hub",
      status: "active",
    }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerProjectTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["project_get"].handler({ name: "logseq-ai-hub" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("project_get");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ name: "logseq-ai-hub" });
  });

  test("returns error when bridge is not connected", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({}));
    const mockBridge = {
      isPluginConnected: () => false,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerProjectTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["project_list"].handler({});

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
    expect(sendRequest.mock.calls.length).toBe(0);
  });

  test("returns error when bridge is absent", async () => {
    const server = createServer();
    registerProjectTools(server, () => ({ db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["project_get"].handler({ name: "some-project" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
  });

  test("returns error when bridge.sendRequest throws", async () => {
    const server = createServer();
    const sendRequest = mock(async () => { throw new Error("project not found"); });
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerProjectTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["project_get"].handler({ name: "missing-project" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("project not found");
  });

  test("project_list result is JSON-serialized in content", async () => {
    const server = createServer();
    const projects = [
      { name: "test-project", repo: "https://github.com/test/test", status: "active" },
    ];
    const sendRequest = mock(async () => ({ projects, count: 1 }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerProjectTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["project_list"].handler({});

    expect(result.isError).toBeUndefined();
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.count).toBe(1);
    expect(parsed.projects[0].name).toBe("test-project");
  });
});
