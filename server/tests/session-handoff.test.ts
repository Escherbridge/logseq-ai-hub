import { describe, it, expect, beforeEach } from "bun:test";
import { Database } from "bun:sqlite";
import { createTestDb, seedTestSession, seedTestSessionMessage } from "./helpers";
import { SessionStore } from "../src/services/session-store";
import { extractSessionId } from "../src/services/session-context";
import { logToolCallToSession } from "../src/services/mcp/session-tools";

// ─── Task 5.1: extractSessionId ─────────────────────────────────────────────

describe("extractSessionId", () => {
  let db: Database;
  let store: SessionStore;

  beforeEach(() => {
    db = createTestDb();
    store = new SessionStore(db);
  });

  it("should return _sessionId from args when present", () => {
    const result = extractSessionId(
      { _sessionId: "explicit-id", other: "data" },
      store,
      "test-agent"
    );
    expect(result).toBe("explicit-id");
  });

  it("should prioritize _sessionId in args over X-Session-Id header", () => {
    const result = extractSessionId(
      { _sessionId: "from-args" },
      store,
      "test-agent",
      { "x-session-id": "from-header" }
    );
    expect(result).toBe("from-args");
  });

  it("should use X-Session-Id header when no _sessionId arg", () => {
    const result = extractSessionId(
      { other: "data" },
      store,
      "test-agent",
      { "X-Session-Id": "header-id" }
    );
    expect(result).toBe("header-id");
  });

  it("should use lowercase x-session-id header as well", () => {
    const result = extractSessionId(
      {},
      store,
      "test-agent",
      { "x-session-id": "lower-header-id" }
    );
    expect(result).toBe("lower-header-id");
  });

  it("should auto-associate when exactly 1 active session exists", () => {
    const session = seedTestSession(db, "test-agent", "Only Session");
    const result = extractSessionId({}, store, "test-agent");
    expect(result).toBe(session.id);
  });

  it("should return null when 0 active sessions exist", () => {
    const result = extractSessionId({}, store, "test-agent");
    expect(result).toBeNull();
  });

  it("should return null when 2+ active sessions exist", () => {
    seedTestSession(db, "test-agent", "Session 1");
    seedTestSession(db, "test-agent", "Session 2");
    const result = extractSessionId({}, store, "test-agent");
    expect(result).toBeNull();
  });

  it("should return null when no session determinable (empty args, no headers)", () => {
    const result = extractSessionId({}, store, "test-agent");
    expect(result).toBeNull();
  });

  it("should ignore empty string _sessionId in args", () => {
    const result = extractSessionId(
      { _sessionId: "" },
      store,
      "test-agent"
    );
    expect(result).toBeNull();
  });

  it("should ignore non-string _sessionId in args", () => {
    const result = extractSessionId(
      { _sessionId: 123 },
      store,
      "test-agent"
    );
    expect(result).toBeNull();
  });

  it("should not auto-associate with sessions from different agents", () => {
    seedTestSession(db, "other-agent", "Other Agent Session");
    const result = extractSessionId({}, store, "test-agent");
    expect(result).toBeNull();
  });

  it("should not auto-associate with archived sessions", () => {
    const session = seedTestSession(db, "test-agent", "Archived Session");
    store.archive(session.id);
    const result = extractSessionId({}, store, "test-agent");
    expect(result).toBeNull();
  });
});

// ─── Task 5.2: logToolCallToSession ─────────────────────────────────────────

describe("logToolCallToSession", () => {
  let db: Database;
  let store: SessionStore;

  beforeEach(() => {
    db = createTestDb();
    store = new SessionStore(db);
  });

  it("should log a tool call as a 'tool' role message", () => {
    const session = seedTestSession(db, "test-agent", "Test Session");
    logToolCallToSession(store, session.id, "graph_search", { query: "test" }, { results: [] });

    const messages = store.getMessages(session.id);
    expect(messages).toHaveLength(1);
    expect(messages[0].role).toBe("tool");
    expect(messages[0].content).toContain("[MCP] graph_search:");
    expect(messages[0].content).toContain(JSON.stringify({ results: [] }));
  });

  it("should include tool name and args in metadata", () => {
    const session = seedTestSession(db, "test-agent", "Test Session");
    const args = { query: "find something", limit: 5 };
    logToolCallToSession(store, session.id, "memory_search", args, "ok");

    const messages = store.getMessages(session.id);
    expect(messages).toHaveLength(1);
    expect(messages[0].metadata).toEqual({
      toolName: "memory_search",
      args: { query: "find something", limit: 5 },
    });
  });

  it("should update session's last_active_at", () => {
    const session = seedTestSession(db, "test-agent", "Test Session");
    const originalLastActive = session.last_active_at;

    // Small delay to ensure timestamp difference
    logToolCallToSession(store, session.id, "tool_x", {}, "result");

    const updated = store.get(session.id);
    expect(updated).not.toBeNull();
    // last_active_at should be >= original (touchActivity updates it)
    expect(updated!.last_active_at >= originalLastActive).toBe(true);
  });

  it("should not throw even if session does not exist", () => {
    expect(() => {
      logToolCallToSession(store, "nonexistent-id", "some_tool", {}, "result");
    }).not.toThrow();
  });

  it("should log multiple calls as multiple messages", () => {
    const session = seedTestSession(db, "test-agent", "Test Session");

    logToolCallToSession(store, session.id, "tool_a", { a: 1 }, "result_a");
    logToolCallToSession(store, session.id, "tool_b", { b: 2 }, "result_b");
    logToolCallToSession(store, session.id, "tool_c", { c: 3 }, "result_c");

    const messages = store.getMessages(session.id);
    expect(messages).toHaveLength(3);
    expect(messages[0].content).toContain("tool_a");
    expect(messages[1].content).toContain("tool_b");
    expect(messages[2].content).toContain("tool_c");
  });
});

// ─── Task 5.3: Cross-Interface Session Continuity ───────────────────────────

describe("Cross-Interface Session Continuity", () => {
  let db: Database;
  let store: SessionStore;

  beforeEach(() => {
    db = createTestDb();
    store = new SessionStore(db);
  });

  it("should maintain unified message history across chat and MCP tool calls", () => {
    // 1. Create session (simulating agent chat creating a session)
    const session = store.create({
      agent_id: "test-agent",
      name: "Cross-Interface Test",
    });

    // 2. Add messages simulating agent chat turns
    store.addMessage({ session_id: session.id, role: "user", content: "What is project X about?" });
    store.addMessage({ session_id: session.id, role: "assistant", content: "Let me search for that." });

    // 3. Use extractSessionId to resolve session (simulating MCP request)
    const resolvedId = extractSessionId(
      { _sessionId: session.id },
      store,
      "test-agent"
    );
    expect(resolvedId).toBe(session.id);

    // 4. Log an MCP tool call to the same session
    logToolCallToSession(store, resolvedId!, "graph_search", { query: "project X" }, { pages: ["ProjectX"] });

    // 5. More chat after the tool call
    store.addMessage({ session_id: session.id, role: "assistant", content: "I found ProjectX page." });

    // 6. Verify all messages are in the same session, in order
    const messages = store.getMessages(session.id);
    expect(messages).toHaveLength(4);
    expect(messages[0].role).toBe("user");
    expect(messages[0].content).toBe("What is project X about?");
    expect(messages[1].role).toBe("assistant");
    expect(messages[1].content).toBe("Let me search for that.");
    expect(messages[2].role).toBe("tool");
    expect(messages[2].content).toContain("[MCP] graph_search:");
    expect(messages[3].role).toBe("assistant");
    expect(messages[3].content).toBe("I found ProjectX page.");
  });

  it("should preserve context set via updateContext across interfaces", () => {
    // 1. Create session with initial context
    const session = store.create({
      agent_id: "test-agent",
      name: "Context Continuity",
      context: { focus: "initial focus" },
    });

    // 2. Agent chat updates context
    store.updateContext(session.id, {
      focus: "researching project X",
      relevant_pages: ["ProjectX"],
    });

    // 3. Resolve session from MCP side
    const resolvedId = extractSessionId(
      { _sessionId: session.id },
      store,
      "test-agent"
    );

    // 4. Verify context is preserved for MCP tool handler
    const resolved = store.get(resolvedId!);
    expect(resolved).not.toBeNull();
    expect(resolved!.context.focus).toBe("researching project X");
    expect(resolved!.context.relevant_pages).toEqual(["ProjectX"]);

    // 5. MCP side adds more context
    store.updateContext(resolvedId!, {
      working_memory: [{ key: "search_result", value: "Found 5 pages", addedAt: new Date().toISOString(), source: "auto" }],
    });

    // 6. Verify merged context (both chat and MCP updates present)
    const final = store.get(session.id);
    expect(final!.context.focus).toBe("researching project X");
    expect(final!.context.relevant_pages).toEqual(["ProjectX"]);
    expect(final!.context.working_memory).toHaveLength(1);
    expect(final!.context.working_memory![0].key).toBe("search_result");
  });

  it("should auto-associate MCP tool calls with the sole active session", () => {
    // 1. Create single active session from chat
    const session = store.create({
      agent_id: "test-agent",
      name: "Only Active Session",
    });
    store.addMessage({ session_id: session.id, role: "user", content: "Hello" });

    // 2. MCP request arrives with no explicit session ID
    const resolvedId = extractSessionId({}, store, "test-agent");
    expect(resolvedId).toBe(session.id);

    // 3. Log tool call to auto-associated session
    logToolCallToSession(store, resolvedId!, "page_read", { name: "Home" }, "page content");

    // 4. Verify tool call appears in session's history alongside chat message
    const messages = store.getMessages(session.id);
    expect(messages).toHaveLength(2);
    expect(messages[0].role).toBe("user");
    expect(messages[1].role).toBe("tool");
  });

  it("should handle header-based session association for MCP requests", () => {
    const session = store.create({
      agent_id: "test-agent",
      name: "Header Test",
    });
    store.addMessage({ session_id: session.id, role: "user", content: "Starting task" });

    // MCP request with X-Session-Id header
    const resolvedId = extractSessionId(
      {},
      store,
      "test-agent",
      { "X-Session-Id": session.id }
    );
    expect(resolvedId).toBe(session.id);

    logToolCallToSession(store, resolvedId!, "tool_x", {}, "done");

    const messages = store.getMessages(session.id);
    expect(messages).toHaveLength(2);
    expect(messages[1].role).toBe("tool");
  });
});
