import { describe, test, expect, mock } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerWorkTools } from "../src/services/mcp/work-tools";
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

const mockWorkStore = {
  claim: mock((sessionId: string, path: string, desc: string) => ({ success: true, sessionId, path, description: desc })),
  release: mock((sessionId: string, path: string) => true),
  releaseAll: mock((sessionId: string) => 0),
  listClaims: mock(() => []),
  checkConflict: mock((path: string) => ({ hasConflict: false })),
} as any;

describe("registerWorkTools", () => {
  test("registers expected tool names", () => {
    const server = createServer();
    registerWorkTools(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);
    expect("work_claim" in tools).toBe(true);
    expect("work_release" in tools).toBe(true);
    expect("work_list_claims" in tools).toBe(true);
    expect("work_log" in tools).toBe(true);
  });

  test("work_claim calls workStore.claim with correct params", async () => {
    const server = createServer();
    const claimMock = mock((_sessionId: string, _path: string, _desc: string) => ({ success: true }));
    const workStore = { ...mockWorkStore, claim: claimMock } as any;
    registerWorkTools(server, () => ({ db: createTestDb(), config: testConfig, workStore }));

    const tools = getRegisteredTools(server);
    await tools["work_claim"].handler({ sessionId: "session-1", path: "/src/file.ts", description: "Refactoring" });

    expect(claimMock.mock.calls.length).toBe(1);
    expect(claimMock.mock.calls[0][0]).toBe("session-1");
    expect(claimMock.mock.calls[0][1]).toBe("/src/file.ts");
    expect(claimMock.mock.calls[0][2]).toBe("Refactoring");
  });

  test("work_claim returns error when workStore not available", async () => {
    const server = createServer();
    registerWorkTools(server, () => ({ db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["work_claim"].handler({ sessionId: "s1", path: "/file.ts", description: "work" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("Work store not available");
  });

  test("work_release calls workStore.release with correct params", async () => {
    const server = createServer();
    const releaseMock = mock((_sessionId: string, _path: string) => true);
    const workStore = { ...mockWorkStore, release: releaseMock } as any;
    registerWorkTools(server, () => ({ db: createTestDb(), config: testConfig, workStore }));

    const tools = getRegisteredTools(server);
    await tools["work_release"].handler({ sessionId: "session-1", path: "/src/file.ts" });

    expect(releaseMock.mock.calls.length).toBe(1);
    expect(releaseMock.mock.calls[0][0]).toBe("session-1");
    expect(releaseMock.mock.calls[0][1]).toBe("/src/file.ts");
  });

  test("work_release returns error when workStore not available", async () => {
    const server = createServer();
    registerWorkTools(server, () => ({ db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["work_release"].handler({ sessionId: "s1", path: "/file.ts" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("Work store not available");
  });

  test("work_list_claims calls workStore.listClaims", async () => {
    const server = createServer();
    const listMock = mock(() => [{ sessionId: "s1", path: "/file.ts", description: "work" }]);
    const workStore = { ...mockWorkStore, listClaims: listMock } as any;
    registerWorkTools(server, () => ({ db: createTestDb(), config: testConfig, workStore }));

    const tools = getRegisteredTools(server);
    const result = await tools["work_list_claims"].handler({});

    expect(listMock.mock.calls.length).toBe(1);
    expect(result.isError).toBeUndefined();
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed).toHaveLength(1);
    expect(parsed[0].sessionId).toBe("s1");
  });

  test("work_list_claims returns error when workStore not available", async () => {
    const server = createServer();
    registerWorkTools(server, () => ({ db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["work_list_claims"].handler({});

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("Work store not available");
  });

  test("work_log calls bridge.sendRequest with work_log operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ logged: true }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerWorkTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["work_log"].handler({ project: "my-project", action: "implemented", details: "Added auth" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("work_log");
  });

  test("work_log returns error when bridge not connected", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({}));
    const mockBridge = {
      isPluginConnected: () => false,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerWorkTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["work_log"].handler({ project: "my-project", action: "implemented", details: "Added auth" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
    expect(sendRequest.mock.calls.length).toBe(0);
  });

  test("work_log passes all params to bridge", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ logged: true }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerWorkTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["work_log"].handler({ project: "my-project", action: "fixed", details: "Fixed the bug in auth module" });

    expect(sendRequest.mock.calls[0][1]).toMatchObject({
      project: "my-project",
      action: "fixed",
      details: "Fixed the bug in auth module",
    });
  });
});
