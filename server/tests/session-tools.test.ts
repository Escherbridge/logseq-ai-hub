import { describe, test, expect, beforeEach } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerSessionTools } from "../src/services/mcp/session-tools";
import { SessionStore } from "../src/services/session-store";
import { createTestDb, seedTestSession, seedTestSessionMessage } from "./helpers";
import type { Config } from "../src/config";
import type { McpToolContext } from "../src/types/mcp";
import { Database } from "bun:sqlite";

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

function setup(db?: Database) {
  const server = createServer();
  const testDb = db ?? createTestDb();
  const sessionStore = new SessionStore(testDb);
  const ctx: McpToolContext = { db: testDb, config: testConfig, sessionStore };
  registerSessionTools(server, () => ctx);
  return { server, db: testDb, sessionStore, tools: getRegisteredTools(server) };
}

// ──────────────────────────────────────────────────────────────────────────────
// Registration
// ──────────────────────────────────────────────────────────────────────────────

describe("registerSessionTools", () => {
  test("registers all expected tool names", () => {
    const { tools } = setup();
    expect("session_create" in tools).toBe(true);
    expect("session_get" in tools).toBe(true);
    expect("session_list" in tools).toBe(true);
    expect("session_update_context" in tools).toBe(true);
    expect("session_set_focus" in tools).toBe(true);
    expect("session_add_memory" in tools).toBe(true);
    expect("session_archive" in tools).toBe(true);
  });

  test("throws when sessionStore is not available", async () => {
    const server = createServer();
    const db = createTestDb();
    registerSessionTools(server, () => ({ db, config: testConfig }));
    const tools = getRegisteredTools(server);

    try {
      await tools["session_create"].handler({ name: "test" });
      expect(true).toBe(false); // should not reach
    } catch (e: any) {
      expect(e.message).toContain("SessionStore not available");
    }
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// session_create
// ──────────────────────────────────────────────────────────────────────────────

describe("session_create", () => {
  test("creates a session with a name", async () => {
    const { tools } = setup();
    const result = await tools["session_create"].handler({ name: "My Session" });
    const session = JSON.parse(result.content[0].text);

    expect(result.isError).toBeUndefined();
    expect(session.name).toBe("My Session");
    expect(session.agent_id).toBe("claude-code");
    expect(session.status).toBe("active");
    expect(session.id).toBeDefined();
  });

  test("creates a session without a name", async () => {
    const { tools } = setup();
    const result = await tools["session_create"].handler({});
    const session = JSON.parse(result.content[0].text);

    expect(result.isError).toBeUndefined();
    expect(session.agent_id).toBe("claude-code");
    expect(session.status).toBe("active");
  });

  test("creates a session with initial context", async () => {
    const { tools } = setup();
    const result = await tools["session_create"].handler({
      name: "With Context",
      context: {
        focus: "building a feature",
        relevant_pages: ["page1", "page2"],
        working_memory: [{ key: "task", value: "implement tests" }],
      },
    });
    const session = JSON.parse(result.content[0].text);

    expect(session.context.focus).toBe("building a feature");
    expect(session.context.relevant_pages).toEqual(["page1", "page2"]);
    expect(session.context.working_memory).toHaveLength(1);
    expect(session.context.working_memory[0].key).toBe("task");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// session_get
// ──────────────────────────────────────────────────────────────────────────────

describe("session_get", () => {
  test("returns session with messages", async () => {
    const db = createTestDb();
    const session = seedTestSession(db, "claude-code", "Test Session");
    seedTestSessionMessage(db, session.id, "user", "Hello");
    seedTestSessionMessage(db, session.id, "assistant", "Hi there");
    const { tools } = setup(db);

    const result = await tools["session_get"].handler({ sessionId: session.id });
    const parsed = JSON.parse(result.content[0].text);

    expect(result.isError).toBeUndefined();
    expect(parsed.session.id).toBe(session.id);
    expect(parsed.session.name).toBe("Test Session");
    expect(parsed.messages).toHaveLength(2);
    expect(parsed.messages[0].role).toBe("user");
    expect(parsed.messages[1].role).toBe("assistant");
  });

  test("respects messageLimit", async () => {
    const db = createTestDb();
    const session = seedTestSession(db, "claude-code", "Test");
    seedTestSessionMessage(db, session.id, "user", "msg1");
    seedTestSessionMessage(db, session.id, "assistant", "msg2");
    seedTestSessionMessage(db, session.id, "user", "msg3");
    const { tools } = setup(db);

    const result = await tools["session_get"].handler({ sessionId: session.id, messageLimit: 2 });
    const parsed = JSON.parse(result.content[0].text);

    expect(parsed.messages).toHaveLength(2);
  });

  test("returns error for non-existent session", async () => {
    const { tools } = setup();
    const result = await tools["session_get"].handler({ sessionId: "non-existent-id" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("Session not found");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// session_list
// ──────────────────────────────────────────────────────────────────────────────

describe("session_list", () => {
  test("lists sessions for claude-code agent", async () => {
    const db = createTestDb();
    seedTestSession(db, "claude-code", "Session 1");
    seedTestSession(db, "claude-code", "Session 2");
    seedTestSession(db, "other-agent", "Other Session");
    const { tools } = setup(db);

    const result = await tools["session_list"].handler({});
    const parsed = JSON.parse(result.content[0].text);

    expect(result.isError).toBeUndefined();
    expect(parsed).toHaveLength(2);
  });

  test("filters by status", async () => {
    const db = createTestDb();
    const s1 = seedTestSession(db, "claude-code", "Active Session");
    const s2 = seedTestSession(db, "claude-code", "Archived Session");
    // Archive the second session
    const store = new SessionStore(db);
    store.archive(s2.id);
    const { tools } = setup(db);

    const result = await tools["session_list"].handler({ status: "archived" });
    const parsed = JSON.parse(result.content[0].text);

    expect(parsed).toHaveLength(1);
    expect(parsed[0].name).toBe("Archived Session");
  });

  test("respects limit", async () => {
    const db = createTestDb();
    seedTestSession(db, "claude-code", "S1");
    seedTestSession(db, "claude-code", "S2");
    seedTestSession(db, "claude-code", "S3");
    const { tools } = setup(db);

    const result = await tools["session_list"].handler({ limit: 2 });
    const parsed = JSON.parse(result.content[0].text);

    expect(parsed).toHaveLength(2);
  });

  test("returns empty list when no sessions exist", async () => {
    const { tools } = setup();
    const result = await tools["session_list"].handler({});
    const parsed = JSON.parse(result.content[0].text);

    expect(parsed).toEqual([]);
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// session_update_context
// ──────────────────────────────────────────────────────────────────────────────

describe("session_update_context", () => {
  test("updates session context with deep merge", async () => {
    const db = createTestDb();
    const session = seedTestSession(db, "claude-code", "Test", { focus: "original" });
    const { tools } = setup(db);

    const result = await tools["session_update_context"].handler({
      sessionId: session.id,
      context: { focus: "updated focus", relevant_pages: ["new-page"] },
    });
    const parsed = JSON.parse(result.content[0].text);

    expect(result.isError).toBeUndefined();
    expect(parsed.context.focus).toBe("updated focus");
    expect(parsed.context.relevant_pages).toContain("new-page");
  });

  test("returns error for non-existent session", async () => {
    const { tools } = setup();
    const result = await tools["session_update_context"].handler({
      sessionId: "non-existent",
      context: { focus: "test" },
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not found");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// session_set_focus
// ──────────────────────────────────────────────────────────────────────────────

describe("session_set_focus", () => {
  test("sets the focus on a session", async () => {
    const db = createTestDb();
    const session = seedTestSession(db, "claude-code", "Focus Test");
    const { tools } = setup(db);

    const result = await tools["session_set_focus"].handler({
      sessionId: session.id,
      focus: "implementing session tools",
    });
    const parsed = JSON.parse(result.content[0].text);

    expect(result.isError).toBeUndefined();
    expect(parsed.context.focus).toBe("implementing session tools");
  });

  test("returns error for non-existent session", async () => {
    const { tools } = setup();
    const result = await tools["session_set_focus"].handler({
      sessionId: "bad-id",
      focus: "test",
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not found");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// session_add_memory
// ──────────────────────────────────────────────────────────────────────────────

describe("session_add_memory", () => {
  test("adds a working memory entry", async () => {
    const db = createTestDb();
    const session = seedTestSession(db, "claude-code", "Memory Test");
    const { tools } = setup(db);

    const result = await tools["session_add_memory"].handler({
      sessionId: session.id,
      key: "user-preference",
      value: "prefers concise responses",
    });
    const parsed = JSON.parse(result.content[0].text);

    expect(result.isError).toBeUndefined();
    expect(parsed.context.working_memory).toHaveLength(1);
    expect(parsed.context.working_memory[0].key).toBe("user-preference");
    expect(parsed.context.working_memory[0].value).toBe("prefers concise responses");
  });

  test("returns error for non-existent session", async () => {
    const { tools } = setup();
    const result = await tools["session_add_memory"].handler({
      sessionId: "bad-id",
      key: "k",
      value: "v",
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not found");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// session_archive
// ──────────────────────────────────────────────────────────────────────────────

describe("session_archive", () => {
  test("archives an existing session", async () => {
    const db = createTestDb();
    const session = seedTestSession(db, "claude-code", "Archive Test");
    const { tools } = setup(db);

    const result = await tools["session_archive"].handler({ sessionId: session.id });
    const parsed = JSON.parse(result.content[0].text);

    expect(result.isError).toBeUndefined();
    expect(parsed.archived).toBe(true);
  });

  test("returns error for non-existent session", async () => {
    const { tools } = setup();
    const result = await tools["session_archive"].handler({ sessionId: "non-existent" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not found");
  });
});
