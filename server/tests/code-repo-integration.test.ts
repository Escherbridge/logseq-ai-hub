import { describe, test, expect, mock } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerAllMcpHandlers } from "../src/services/mcp/index";
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

function getRegisteredResources(server: McpServer): Record<string, any> {
  return (server as any)._registeredResources ?? {};
}

function getRegisteredResourceTemplates(server: McpServer): Record<string, any> {
  return (server as any)._registeredResourceTemplates ?? {};
}

function getRegisteredPrompts(server: McpServer): Record<string, any> {
  return (server as any)._registeredPrompts ?? {};
}

describe("code-repo integration", () => {
  test("all code-repo tools are registered", () => {
    const server = createServer();
    registerAllMcpHandlers(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);

    // Project tools (2)
    expect("project_list" in tools).toBe(true);
    expect("project_get" in tools).toBe(true);

    // ADR tools (2)
    expect("adr_list" in tools).toBe(true);
    expect("adr_create" in tools).toBe(true);

    // Lesson tools (2)
    expect("lesson_store" in tools).toBe(true);
    expect("lesson_search" in tools).toBe(true);

    // Safeguard tools (5)
    expect("safeguard_check" in tools).toBe(true);
    expect("safeguard_request" in tools).toBe(true);
    expect("safeguard_policy_get" in tools).toBe(true);
    expect("safeguard_policy_update" in tools).toBe(true);
    expect("safeguard_audit_log" in tools).toBe(true);

    // Work tools (4)
    expect("work_claim" in tools).toBe(true);
    expect("work_release" in tools).toBe(true);
    expect("work_list_claims" in tools).toBe(true);
    expect("work_log" in tools).toBe(true);

    // Task tools (7)
    expect("track_create" in tools).toBe(true);
    expect("track_list" in tools).toBe(true);
    expect("track_update" in tools).toBe(true);
    expect("task_add" in tools).toBe(true);
    expect("task_update" in tools).toBe(true);
    expect("task_list" in tools).toBe(true);
    expect("project_dashboard" in tools).toBe(true);

    // Pi.dev tools (9)
    expect("pi_spawn" in tools).toBe(true);
    expect("pi_send" in tools).toBe(true);
    expect("pi_status" in tools).toBe(true);
    expect("pi_stop" in tools).toBe(true);
    expect("pi_list_sessions" in tools).toBe(true);
    expect("pi_agent_create" in tools).toBe(true);
    expect("pi_agent_list" in tools).toBe(true);
    expect("pi_agent_get" in tools).toBe(true);
    expect("pi_agent_update" in tools).toBe(true);

    // Event tools (7)
    expect("event_publish" in tools).toBe(true);
    expect("event_query" in tools).toBe(true);
    expect("event_subscribe" in tools).toBe(true);
    expect("event_sources" in tools).toBe(true);
    expect("event_recent" in tools).toBe(true);
    expect("webhook_test" in tools).toBe(true);
    expect("http_request" in tools).toBe(true);
  });

  test("work_claim and work_release lifecycle", async () => {
    const server = createServer();
    const { WorkClaimStore } = await import("../src/services/work-store");
    const workStore = new WorkClaimStore();
    registerAllMcpHandlers(server, () => ({ db: createTestDb(), config: testConfig, workStore }));
    const tools = getRegisteredTools(server);

    // Claim a file path
    const claimResult = await tools["work_claim"].handler({
      sessionId: "session-1",
      path: "src/api/users.ts",
      description: "Adding user validation",
    });
    expect(claimResult.isError).toBeUndefined();

    // List claims shows it
    const listResult = await tools["work_list_claims"].handler({});
    const claims = JSON.parse(listResult.content[0].text);
    expect(claims.length).toBe(1);

    // Release it
    const releaseResult = await tools["work_release"].handler({
      sessionId: "session-1",
      path: "src/api/users.ts",
    });
    expect(releaseResult.isError).toBeUndefined();

    // List now empty
    const listAfter = await tools["work_list_claims"].handler({});
    const claimsAfter = JSON.parse(listAfter.content[0].text);
    expect(claimsAfter.length).toBe(0);
  });

  test("safeguard_check returns action when no bridge", async () => {
    const server = createServer();
    const { SafeguardService } = await import("../src/services/safeguard-service");
    const safeguardService = new SafeguardService(undefined as any, undefined as any);
    registerAllMcpHandlers(server, () => ({ db: createTestDb(), config: testConfig, safeguardService }));
    const tools = getRegisteredTools(server);

    const result = await tools["safeguard_check"].handler({
      project: "test-project",
      operation: "deploy",
      agent: "agent-1",
      details: "deploying v1.0",
    });
    // Should return a result (even if error due to no bridge)
    expect(result.content[0].text).toBeDefined();
  });

  test("project_dashboard returns error when bridge not connected", async () => {
    const server = createServer();
    registerAllMcpHandlers(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);

    const result = await tools["project_dashboard"].handler({ project: "test" });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
  });

  test("pi_spawn returns disabled error when no piDevManager", async () => {
    const server = createServer();
    registerAllMcpHandlers(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);

    const result = await tools["pi_spawn"].handler({ project: "test", task: "test" });
    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not available");
  });

  test("all code-repo resources are registered", () => {
    const server = createServer();
    registerAllMcpHandlers(server, () => ({ db: createTestDb(), config: testConfig }));
    const templates = getRegisteredResourceTemplates(server);

    expect("logseq-project" in templates).toBe(true);
    expect("logseq-project-adrs" in templates).toBe(true);
    expect("logseq-project-lessons" in templates).toBe(true);
    expect("logseq-project-tracks" in templates).toBe(true);
    expect("logseq-project-safeguards" in templates).toBe(true);
  });

  test("all code-repo prompts are registered", () => {
    const server = createServer();
    registerAllMcpHandlers(server, () => ({ db: createTestDb(), config: testConfig }));
    const prompts = getRegisteredPrompts(server);

    expect("code_review" in prompts).toBe(true);
    expect("start_coding_session" in prompts).toBe(true);
    expect("deployment_checklist" in prompts).toBe(true);
  });

  test("code_review prompt includes project name", async () => {
    const server = createServer();
    registerAllMcpHandlers(server, () => ({ db: createTestDb(), config: testConfig }));
    const prompts = getRegisteredPrompts(server);

    const result = await prompts["code_review"].callback({ project: "my-app", diff: "- old\n+ new" });
    expect(result.messages[0].content.text).toContain("my-app");
    expect(result.messages[0].content.text).toContain("- old");
  });

  test("start_coding_session prompt includes task", async () => {
    const server = createServer();
    registerAllMcpHandlers(server, () => ({ db: createTestDb(), config: testConfig }));
    const prompts = getRegisteredPrompts(server);

    const result = await prompts["start_coding_session"].callback({ project: "my-app", task: "fix auth bug" });
    expect(result.messages[0].content.text).toContain("my-app");
    expect(result.messages[0].content.text).toContain("fix auth bug");
  });

  test("deployment_checklist prompt includes environment", async () => {
    const server = createServer();
    registerAllMcpHandlers(server, () => ({ db: createTestDb(), config: testConfig }));
    const prompts = getRegisteredPrompts(server);

    const result = await prompts["deployment_checklist"].callback({ project: "my-app", environment: "production" });
    expect(result.messages[0].content.text).toContain("my-app");
    expect(result.messages[0].content.text).toContain("production");
  });
});
