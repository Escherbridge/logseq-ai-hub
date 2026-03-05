import { describe, test, expect } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerResources } from "../src/services/mcp/resources";
import { registerPrompts } from "../src/services/mcp/prompts";
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

/**
 * The MCP SDK stores registrations as plain objects keyed by name or URI.
 * Static resources: keyed by URI string (e.g. "logseq://jobs")
 * Resource templates: keyed by name (e.g. "logseq-page")
 * Prompts: keyed by name (e.g. "summarize_page")
 * Tools: keyed by name (e.g. "graph_query")
 */
function getRegisteredResources(server: McpServer): Record<string, any> {
  return (server as any)._registeredResources ?? {};
}

function getRegisteredResourceTemplates(server: McpServer): Record<string, any> {
  return (server as any)._registeredResourceTemplates ?? {};
}

function getRegisteredPrompts(server: McpServer): Record<string, any> {
  return (server as any)._registeredPrompts ?? {};
}

function getRegisteredTools(server: McpServer): Record<string, any> {
  return (server as any)._registeredTools ?? {};
}

// ──────────────────────────────────────────────────────────────────────────────
// Resources
// ──────────────────────────────────────────────────────────────────────────────

describe("registerResources", () => {
  test("registers exactly 5 resources/templates in total", () => {
    const server = createServer();
    registerResources(server, () => ({ db: createTestDb(), config: testConfig }));

    const staticCount = Object.keys(getRegisteredResources(server)).length;
    const templateCount = Object.keys(getRegisteredResourceTemplates(server)).length;

    expect(staticCount + templateCount).toBe(6);
  });

  test("registers logseq-page as a resource template (keyed by name)", () => {
    const server = createServer();
    registerResources(server, () => ({ db: createTestDb(), config: testConfig }));
    const templates = getRegisteredResourceTemplates(server);
    expect("logseq-page" in templates).toBe(true);
  });

  test("registers logseq-jobs as a static resource (keyed by URI)", () => {
    const server = createServer();
    registerResources(server, () => ({ db: createTestDb(), config: testConfig }));
    const resources = getRegisteredResources(server);
    expect("logseq://jobs" in resources).toBe(true);
  });

  test("registers logseq-skills as a static resource (keyed by URI)", () => {
    const server = createServer();
    registerResources(server, () => ({ db: createTestDb(), config: testConfig }));
    const resources = getRegisteredResources(server);
    expect("logseq://skills" in resources).toBe(true);
  });

  test("registers logseq-memory as a resource template (keyed by name)", () => {
    const server = createServer();
    registerResources(server, () => ({ db: createTestDb(), config: testConfig }));
    const templates = getRegisteredResourceTemplates(server);
    expect("logseq-memory" in templates).toBe(true);
  });

  test("registers logseq-contacts as a static resource (keyed by URI)", () => {
    const server = createServer();
    registerResources(server, () => ({ db: createTestDb(), config: testConfig }));
    const resources = getRegisteredResources(server);
    expect("logseq://contacts" in resources).toBe(true);
  });

  test("static resources have name metadata matching the registration name", () => {
    const server = createServer();
    registerResources(server, () => ({ db: createTestDb(), config: testConfig }));
    const resources = getRegisteredResources(server);
    expect(resources["logseq://jobs"].name).toBe("logseq-jobs");
    expect(resources["logseq://skills"].name).toBe("logseq-skills");
    expect(resources["logseq://contacts"].name).toBe("logseq-contacts");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// Prompts
// ──────────────────────────────────────────────────────────────────────────────

describe("registerPrompts", () => {
  test("registers exactly 4 prompts", () => {
    const server = createServer();
    registerPrompts(server);
    const prompts = getRegisteredPrompts(server);
    expect(Object.keys(prompts).length).toBe(4);
  });

  test("registers summarize_page prompt", () => {
    const server = createServer();
    registerPrompts(server);
    expect("summarize_page" in getRegisteredPrompts(server)).toBe(true);
  });

  test("registers create_skill_from_description prompt", () => {
    const server = createServer();
    registerPrompts(server);
    expect("create_skill_from_description" in getRegisteredPrompts(server)).toBe(true);
  });

  test("registers analyze_knowledge_base prompt", () => {
    const server = createServer();
    registerPrompts(server);
    expect("analyze_knowledge_base" in getRegisteredPrompts(server)).toBe(true);
  });

  test("registers draft_message prompt", () => {
    const server = createServer();
    registerPrompts(server);
    expect("draft_message" in getRegisteredPrompts(server)).toBe(true);
  });

  test("prompts return messages array in their callback response", async () => {
    const server = createServer();
    registerPrompts(server);
    const prompts = getRegisteredPrompts(server);

    const result = await prompts["summarize_page"].callback({ page: "My Notes" });
    expect(Array.isArray(result.messages)).toBe(true);
    expect(result.messages.length).toBeGreaterThan(0);
    expect(result.messages[0].role).toBe("user");
    expect(result.messages[0].content.type).toBe("text");
    expect(result.messages[0].content.text).toContain("My Notes");
  });

  test("draft_message prompt includes contact and context in message text", async () => {
    const server = createServer();
    registerPrompts(server);
    const prompts = getRegisteredPrompts(server);

    const result = await prompts["draft_message"].callback({ contact: "Alice", context: "project update" });
    const text = result.messages[0].content.text;
    expect(text).toContain("Alice");
    expect(text).toContain("project update");
  });

  test("analyze_knowledge_base uses generic text when no focus given", async () => {
    const server = createServer();
    registerPrompts(server);
    const prompts = getRegisteredPrompts(server);

    const result = await prompts["analyze_knowledge_base"].callback({});
    const text = result.messages[0].content.text;
    expect(text).toContain("knowledge base");
  });

  test("analyze_knowledge_base uses focus text when provided", async () => {
    const server = createServer();
    registerPrompts(server);
    const prompts = getRegisteredPrompts(server);

    const result = await prompts["analyze_knowledge_base"].callback({ focus: "machine learning" });
    const text = result.messages[0].content.text;
    expect(text).toContain("machine learning");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// registerAllMcpHandlers - combined registration
// ──────────────────────────────────────────────────────────────────────────────

describe("registerAllMcpHandlers", () => {
  test("registers all tools, resources, and prompts together", () => {
    const server = createServer();
    registerAllMcpHandlers(server, () => ({ db: createTestDb(), config: testConfig }));

    const toolCount = Object.keys(getRegisteredTools(server)).length;
    const staticResourceCount = Object.keys(getRegisteredResources(server)).length;
    const templateResourceCount = Object.keys(getRegisteredResourceTemplates(server)).length;
    const promptCount = Object.keys(getRegisteredPrompts(server)).length;

    // Tools: 7 graph + 10 job + 4 memory + 3 messaging + 1 approval + 4 registry + 7 session + 2 project + 2 adr + 2 lesson + 5 safeguard + 4 work + 7 task = 58
    expect(toolCount).toBe(58);

    // Resources: 6 total (3 static + 3 templates)
    expect(staticResourceCount + templateResourceCount).toBe(6);

    // Prompts: 4
    expect(promptCount).toBe(4);
  });

  test("getMcpStatus reflects registered counts after registerAllMcpHandlers on the singleton", async () => {
    const { createMcpServer, getMcpStatus } = await import("../src/services/mcp-server");
    // createMcpServer is idempotent — returns the same singleton
    const server = createMcpServer();
    // Register handlers on the singleton (idempotent registration would double-count,
    // but since each test file gets fresh server instances for the other tests,
    // we only call this once here via the singleton path)
    registerAllMcpHandlers(server, () => ({ db: createTestDb(), config: testConfig }));
    const status = getMcpStatus();

    // At least 24 tools and 5 resources registered
    expect(status.toolCount).toBeGreaterThanOrEqual(24);
    expect(status.resourceCount).toBeGreaterThanOrEqual(5);
    expect(status.promptCount).toBeGreaterThanOrEqual(4);
  });
});
