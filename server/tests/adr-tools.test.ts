import { describe, test, expect, mock } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerAdrTools } from "../src/services/mcp/adr-tools";
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

describe("registerAdrTools", () => {
  test("registers without throwing", () => {
    const server = createServer();
    expect(() => {
      registerAdrTools(server, () => ({ db: createTestDb(), config: testConfig }));
    }).not.toThrow();
  });

  test("registers expected tool names", () => {
    const server = createServer();
    registerAdrTools(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);
    expect("adr_list" in tools).toBe(true);
    expect("adr_create" in tools).toBe(true);
  });

  test("returns error when bridge is not connected", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({}));
    const mockBridge = {
      isPluginConnected: () => false,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerAdrTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["adr_list"].handler({ project: "logseq-ai-hub" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
    expect(sendRequest.mock.calls.length).toBe(0);
  });

  test("returns error when bridge is absent", async () => {
    const server = createServer();
    registerAdrTools(server, () => ({ db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["adr_create"].handler({
      project: "logseq-ai-hub",
      title: "Use SSE bridge",
      context: "Need real-time comms",
      decision: "Use SSE",
      consequences: "One-directional push",
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
  });

  test("bridge error propagation on adr_list", async () => {
    const server = createServer();
    const sendRequest = mock(async () => { throw new Error("ADR page not found"); });
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerAdrTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["adr_list"].handler({ project: "missing-project" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("ADR page not found");
  });

  test("bridge error propagation on adr_create", async () => {
    const server = createServer();
    const sendRequest = mock(async () => { throw new Error("failed to create ADR"); });
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerAdrTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["adr_create"].handler({
      project: "logseq-ai-hub",
      title: "Use SSE bridge",
      context: "Need real-time comms",
      decision: "Use SSE",
      consequences: "One-directional push",
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("failed to create ADR");
  });

  test("adr_list calls bridge.sendRequest with 'adr_list' operation", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({ adrs: [], count: 0 }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerAdrTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["adr_list"].handler({ project: "logseq-ai-hub" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("adr_list");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ project: "logseq-ai-hub" });
  });

  test("adr_create calls bridge.sendRequest with 'adr_create' operation", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({ adr: { number: 1, title: "Use SSE bridge" } }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerAdrTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["adr_create"].handler({
      project: "logseq-ai-hub",
      title: "Use SSE bridge",
      context: "Need real-time comms",
      decision: "Use SSE",
      consequences: "One-directional push",
      status: "accepted",
    });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("adr_create");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({
      project: "logseq-ai-hub",
      title: "Use SSE bridge",
      status: "accepted",
    });
  });

  test("adr_list result is JSON-serialized in content", async () => {
    const server = createServer();
    const adrs = [
      { number: 1, title: "Use SSE bridge", status: "accepted", date: "2026-03-05" },
    ];
    const sendRequest = mock(async () => ({ adrs, count: 1 }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerAdrTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["adr_list"].handler({ project: "logseq-ai-hub" });

    expect(result.isError).toBeUndefined();
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.count).toBe(1);
    expect(parsed.adrs[0].title).toBe("Use SSE bridge");
  });
});
