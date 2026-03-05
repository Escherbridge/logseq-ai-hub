import { describe, test, expect, beforeEach, mock } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerSafeguardTools } from "../src/services/mcp/safeguard-tools";
import type { McpToolContext } from "../src/types/mcp";
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

describe("registerSafeguardTools", () => {
  test("registers without throwing", () => {
    const server = createServer();
    expect(() => {
      registerSafeguardTools(server, () => ({ db: createTestDb(), config: testConfig }));
    }).not.toThrow();
  });

  test("registers all 5 expected tool names", () => {
    const server = createServer();
    registerSafeguardTools(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);
    expect("safeguard_check" in tools).toBe(true);
    expect("safeguard_request" in tools).toBe(true);
    expect("safeguard_policy_get" in tools).toBe(true);
    expect("safeguard_policy_update" in tools).toBe(true);
    expect("safeguard_audit_log" in tools).toBe(true);
  });

  test("safeguard_policy_get returns error when bridge is disconnected", async () => {
    const server = createServer();
    const mockBridge = {
      isPluginConnected: () => false,
      sendRequest: mock(async () => ({})),
      pendingCount: 0,
    } as any;
    registerSafeguardTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["safeguard_policy_get"].handler({ project: "my-project" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
  });

  test("safeguard_audit_log returns error when bridge is disconnected", async () => {
    const server = createServer();
    const mockBridge = {
      isPluginConnected: () => false,
      sendRequest: mock(async () => ({})),
      pendingCount: 0,
    } as any;
    registerSafeguardTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["safeguard_audit_log"].handler({ project: "my-project" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
  });

  test("safeguard_policy_get returns error when bridge is absent", async () => {
    const server = createServer();
    registerSafeguardTools(server, () => ({ db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["safeguard_policy_get"].handler({ project: "my-project" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
  });

  test("safeguard_policy_get calls bridge.sendRequest with correct operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ level: 1, rules: [], contact: null }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerSafeguardTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["safeguard_policy_get"].handler({ project: "my-project" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("safeguard_policy_get");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ project: "my-project" });
  });

  test("safeguard_audit_log calls bridge.sendRequest with correct operation and params", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({ entries: [] }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerSafeguardTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["safeguard_audit_log"].handler({ project: "my-project", agent: "test-agent" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("safeguard_audit_log");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ project: "my-project", agent: "test-agent" });
  });

  test("safeguard_check returns allow fallback when no safeguardService configured", async () => {
    const server = createServer();
    registerSafeguardTools(server, () => ({ db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["safeguard_check"].handler({
      project: "my-project",
      operation: "force push",
      agent: "test-agent",
      details: "pushing hotfix",
    });

    expect(result.isError).toBeUndefined();
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.action).toBe("allow");
    expect(parsed.reason).toContain("not configured");
  });

  test("safeguard_request returns error when no safeguardService configured", async () => {
    const server = createServer();
    registerSafeguardTools(server, () => ({ db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["safeguard_request"].handler({
      project: "my-project",
      operation: "deploy to production",
      agent: "test-agent",
      details: "release v2.0",
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not configured");
  });

  test("safeguard_check uses safeguardService when available", async () => {
    const server = createServer();
    const evaluatePolicy = mock(async () => ({ action: "allow", reason: "Permitted by policy" }));
    const logAudit = mock(async () => {});
    const mockSafeguardService = { evaluatePolicy, logAudit } as any;

    registerSafeguardTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      safeguardService: mockSafeguardService,
    } as any));

    const tools = getRegisteredTools(server);
    const result = await tools["safeguard_check"].handler({
      project: "my-project",
      operation: "force push",
      agent: "test-agent",
      details: "emergency fix",
    });

    expect(result.isError).toBeUndefined();
    expect(evaluatePolicy.mock.calls.length).toBe(1);
    expect(logAudit.mock.calls.length).toBe(1);
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.action).toBe("allow");
  });

  test("safeguard_request uses safeguardService when available", async () => {
    const server = createServer();
    const requestApproval = mock(async () => ({ approved: true, approvedBy: "user:alice", at: new Date().toISOString() }));
    const mockSafeguardService = { requestApproval } as any;

    registerSafeguardTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      safeguardService: mockSafeguardService,
    } as any));

    const tools = getRegisteredTools(server);
    const result = await tools["safeguard_request"].handler({
      project: "my-project",
      operation: "deploy to production",
      agent: "test-agent",
      details: "release v2.0",
    });

    expect(result.isError).toBeUndefined();
    expect(requestApproval.mock.calls.length).toBe(1);
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.approved).toBe(true);
  });

  test("safeguard_policy_update requires approval for level 2+ policies", async () => {
    const server = createServer();
    const getPolicy = mock(async () => ({ level: 2, rules: [], contact: null }));
    const requestApproval = mock(async () => ({ approved: false }));
    const mockSafeguardService = { getPolicy, requestApproval } as any;

    registerSafeguardTools(server, () => ({
      db: createTestDb(),
      config: testConfig,
      safeguardService: mockSafeguardService,
    } as any));

    const tools = getRegisteredTools(server);
    const result = await tools["safeguard_policy_update"].handler({
      project: "my-project",
      rules: "- BLOCK: force push to main",
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("approval required");
    expect(getPolicy.mock.calls.length).toBe(1);
    expect(requestApproval.mock.calls.length).toBe(1);
  });

  test("safeguard_policy_update proceeds via bridge for level 1 policy", async () => {
    const server = createServer();
    const getPolicy = mock(async () => ({ level: 1, rules: [], contact: null }));
    const mockSafeguardService = { getPolicy } as any;
    const sendRequest = mock(async () => ({ updated: true }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;

    registerSafeguardTools(server, () => ({
      bridge: mockBridge,
      db: createTestDb(),
      config: testConfig,
      safeguardService: mockSafeguardService,
    } as any));

    const tools = getRegisteredTools(server);
    const result = await tools["safeguard_policy_update"].handler({
      project: "my-project",
      rules: "- BLOCK: force push to main",
    });

    expect(result.isError).toBeUndefined();
    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("safeguard_policy_update");
  });

  test("safeguard_policy_get result is JSON-serialized in content", async () => {
    const server = createServer();
    const policy = { level: 1, rules: ["BLOCK: force push"], contact: "telegram:123" };
    const sendRequest = mock(async () => policy);
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerSafeguardTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["safeguard_policy_get"].handler({ project: "my-project" });

    expect(result.isError).toBeUndefined();
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.level).toBe(1);
    expect(parsed.contact).toBe("telegram:123");
  });
});
