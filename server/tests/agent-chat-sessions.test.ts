import { describe, test, expect, beforeEach, mock, type Mock } from "bun:test";
import { Database } from "bun:sqlite";
import { createTestDb, seedTestSession, seedTestSessionMessage } from "./helpers";
import { SessionStore } from "../src/services/session-store";
import { handleAgentChat } from "../src/routes/api/agent-chat";
import type { Config } from "../src/config";
import type { AgentBridge } from "../src/services/agent-bridge";

// ---------------------------------------------------------------------------
// Mock the LLM module so we never hit a real API
// ---------------------------------------------------------------------------
const mockChatCompletion = mock(() =>
  Promise.resolve({ content: "Hello from LLM", toolCalls: undefined })
);

mock.module("../src/services/llm", () => ({
  chatCompletion: (...args: any[]) => mockChatCompletion(...args),
}));

// ---------------------------------------------------------------------------
// Shared test config
// ---------------------------------------------------------------------------
const testConfig: Config = {
  port: 3000,
  whatsappVerifyToken: "",
  whatsappAccessToken: "",
  whatsappPhoneNumberId: "",
  telegramBotToken: "",
  pluginApiToken: "test-token",
  databasePath: ":memory:",
  llmApiKey: "test-key",
  llmEndpoint: "https://test.api/v1",
  agentModel: "test-model",
  agentRequestTimeout: 5000,
  sessionMessageLimit: 50,
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------
function makeRequest(
  body: Record<string, unknown>,
  token = testConfig.pluginApiToken
): Request {
  return new Request("http://localhost/api/agent/chat", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  });
}

function makeBridge(connected = false): AgentBridge {
  return {
    isPluginConnected: () => connected,
    sendRequest: mock(() => Promise.resolve({})),
  } as unknown as AgentBridge;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
describe("Agent Chat with Sessions", () => {
  let db: Database;
  let store: SessionStore;
  let bridge: AgentBridge;

  beforeEach(() => {
    db = createTestDb();
    store = new SessionStore(db);
    bridge = makeBridge(false);
    mockChatCompletion.mockReset();
    mockChatCompletion.mockImplementation(() =>
      Promise.resolve({ content: "Hello from LLM", toolCalls: undefined })
    );
  });

  // ── Auth ──────────────────────────────────────────────────────────────────
  describe("authentication", () => {
    test("returns 401 without auth token", async () => {
      const req = makeRequest({ message: "hi" }, "wrong-token");
      const res = await handleAgentChat(req, testConfig, bridge, store);
      expect(res.status).toBe(401);
    });

    test("returns 401 with no Authorization header", async () => {
      const req = new Request("http://localhost/api/agent/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ message: "hi" }),
      });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      expect(res.status).toBe(401);
    });
  });

  // ── Validation ────────────────────────────────────────────────────────────
  describe("validation", () => {
    test("returns 400 for missing message", async () => {
      const req = makeRequest({});
      const res = await handleAgentChat(req, testConfig, bridge, store);
      expect(res.status).toBe(400);
      const body = await res.json();
      expect(body.error).toContain("message");
    });

    test("returns 400 for empty message", async () => {
      const req = makeRequest({ message: "   " });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      expect(res.status).toBe(400);
    });

    test("returns 400 for invalid JSON body", async () => {
      const req = new Request("http://localhost/api/agent/chat", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${testConfig.pluginApiToken}`,
        },
        body: "not json",
      });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      expect(res.status).toBe(400);
    });

    test("returns 503 when LLM API key not configured", async () => {
      const noLlmConfig = { ...testConfig, llmApiKey: "" };
      const req = makeRequest({ message: "hi" });
      const res = await handleAgentChat(req, noLlmConfig, bridge, store);
      expect(res.status).toBe(503);
    });
  });

  // ── Session Creation ──────────────────────────────────────────────────────
  describe("session creation", () => {
    test("creates new session when no sessionId provided", async () => {
      const req = makeRequest({ message: "hello" });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      expect(res.status).toBe(200);
      const body = await res.json();
      expect(body.success).toBe(true);
      expect(body.data.sessionId).toBeDefined();
      expect(typeof body.data.sessionId).toBe("string");
      expect(body.data.response).toBe("Hello from LLM");

      // Session should exist in store
      const session = store.get(body.data.sessionId);
      expect(session).not.toBeNull();
      expect(session!.agent_id).toBe("agent-chat");
    });

    test("loads existing session when valid sessionId provided", async () => {
      const existing = seedTestSession(db, "agent-chat", "My Session");
      const req = makeRequest({ message: "hello", sessionId: existing.id });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      expect(res.status).toBe(200);
      const body = await res.json();
      expect(body.data.sessionId).toBe(existing.id);
    });

    test("creates new session when invalid sessionId provided (graceful fallback)", async () => {
      const req = makeRequest({ message: "hello", sessionId: "nonexistent-id" });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      expect(res.status).toBe(200);
      const body = await res.json();
      expect(body.data.sessionId).toBeDefined();
      expect(body.data.sessionId).not.toBe("nonexistent-id");
    });

    test("backward compat: conversationId maps to sessionId", async () => {
      const existing = seedTestSession(db, "agent-chat", "Compat Session");
      const req = makeRequest({
        message: "hello",
        conversationId: existing.id,
      });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      expect(res.status).toBe(200);
      const body = await res.json();
      expect(body.data.sessionId).toBe(existing.id);
    });
  });

  // ── Message Storage ───────────────────────────────────────────────────────
  describe("message storage", () => {
    test("stores user and assistant messages in session_messages table", async () => {
      const req = makeRequest({ message: "test message" });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      const body = await res.json();
      const sessionId = body.data.sessionId;

      const messages = store.getMessages(sessionId);
      // Should have: user message + assistant response
      expect(messages.length).toBe(2);
      expect(messages[0].role).toBe("user");
      expect(messages[0].content).toBe("test message");
      expect(messages[1].role).toBe("assistant");
      expect(messages[1].content).toBe("Hello from LLM");
    });

    test("system prompt is NOT stored in messages (regenerated each time)", async () => {
      const req = makeRequest({ message: "hello" });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      const body = await res.json();
      const sessionId = body.data.sessionId;

      const messages = store.getMessages(sessionId);
      const systemMessages = messages.filter((m) => m.role === "system");
      expect(systemMessages.length).toBe(0);
    });

    test("messages accumulate across turns in same session", async () => {
      // First turn
      const req1 = makeRequest({ message: "first" });
      const res1 = await handleAgentChat(req1, testConfig, bridge, store);
      const body1 = await res1.json();
      const sessionId = body1.data.sessionId;

      // Second turn
      const req2 = makeRequest({ message: "second", sessionId });
      await handleAgentChat(req2, testConfig, bridge, store);

      const messages = store.getMessages(sessionId);
      // user1 + assistant1 + user2 + assistant2
      expect(messages.length).toBe(4);
      expect(messages[0].content).toBe("first");
      expect(messages[2].content).toBe("second");
    });
  });

  // ── Session Context in System Prompt ──────────────────────────────────────
  describe("session context in system prompt", () => {
    test("includes session context in LLM system message", async () => {
      const session = seedTestSession(db, "agent-chat", "Context Session", {
        focus: "Writing tests",
        working_memory: [
          { key: "lang", value: "TypeScript", addedAt: new Date().toISOString(), source: "manual" },
        ],
      });

      const req = makeRequest({ message: "hello", sessionId: session.id });
      await handleAgentChat(req, testConfig, bridge, store);

      // chatCompletion should have been called with a system message containing context
      expect(mockChatCompletion).toHaveBeenCalled();
      const callArgs = mockChatCompletion.mock.calls[0];
      const messages = callArgs[0] as Array<{ role: string; content: string }>;
      const systemMsg = messages.find((m) => m.role === "system");
      expect(systemMsg).toBeDefined();
      expect(systemMsg!.content).toContain("Writing tests");
      expect(systemMsg!.content).toContain("lang");
      expect(systemMsg!.content).toContain("TypeScript");
    });

    test("resolves relevant pages via bridge when available", async () => {
      // Create a bridge that returns page content when sendRequest is called
      const connectedBridge = {
        isPluginConnected: () => true,
        sendRequest: mock(async (operation: string, params: any) => {
          if (operation === "page_read" && params.name === "TestPage") {
            return "Page content here";
          }
          return {};
        }),
      } as unknown as AgentBridge;

      const session = seedTestSession(db, "agent-chat", "Pages Session", {
        relevant_pages: ["TestPage"],
      });

      const req = makeRequest({ message: "hello", sessionId: session.id });
      await handleAgentChat(req, testConfig, connectedBridge, store);

      // The real resolveRelevantPages should have called bridge.sendRequest
      expect(connectedBridge.sendRequest).toHaveBeenCalled();

      const callArgs = mockChatCompletion.mock.calls[0];
      const messages = callArgs[0] as Array<{ role: string; content: string }>;
      const systemMsg = messages.find((m) => m.role === "system");
      expect(systemMsg!.content).toContain("TestPage");
      expect(systemMsg!.content).toContain("Page content here");
    });
  });

  // ── Tool Calls ────────────────────────────────────────────────────────────
  describe("tool calls", () => {
    test("stores tool calls and tool results in session messages", async () => {
      const connectedBridge = makeBridge(true);
      (connectedBridge.sendRequest as Mock<any>).mockImplementation(() =>
        Promise.resolve({ pages: ["Page1"] })
      );

      // First call returns tool call, second returns final response
      let callCount = 0;
      mockChatCompletion.mockImplementation(() => {
        callCount++;
        if (callCount === 1) {
          return Promise.resolve({
            content: "",
            toolCalls: [
              {
                id: "call_123",
                function: {
                  name: "page_list",
                  arguments: JSON.stringify({ pattern: "test" }),
                },
              },
            ],
          });
        }
        return Promise.resolve({
          content: "Found pages for you",
          toolCalls: undefined,
        });
      });

      const req = makeRequest({ message: "list pages" });
      const res = await handleAgentChat(req, testConfig, connectedBridge, store);
      const body = await res.json();
      const sessionId = body.data.sessionId;

      expect(body.data.response).toBe("Found pages for you");
      expect(body.data.actions).toBeDefined();
      expect(body.data.actions.length).toBe(1);
      expect(body.data.actions[0].operation).toBe("page_list");

      // Check stored messages: user, assistant(tool_calls), tool, assistant(final)
      const messages = store.getMessages(sessionId);
      expect(messages.length).toBe(4);

      // user message
      expect(messages[0].role).toBe("user");

      // assistant with tool_calls
      expect(messages[1].role).toBe("assistant");
      expect(messages[1].tool_calls).toBeDefined();
      expect(messages[1].tool_calls!.length).toBe(1);
      expect(messages[1].tool_calls![0].id).toBe("call_123");

      // tool result
      expect(messages[2].role).toBe("tool");
      expect(messages[2].tool_call_id).toBe("call_123");

      // final assistant response
      expect(messages[3].role).toBe("assistant");
      expect(messages[3].content).toBe("Found pages for you");
    });

    test("handles plugin not connected during tool execution", async () => {
      const disconnectedBridge = makeBridge(false);

      // Return tool call but bridge is disconnected
      let callCount = 0;
      mockChatCompletion.mockImplementation(() => {
        callCount++;
        if (callCount === 1) {
          return Promise.resolve({
            content: "",
            toolCalls: [
              {
                id: "call_456",
                function: {
                  name: "page_read",
                  arguments: JSON.stringify({ name: "test" }),
                },
              },
            ],
          });
        }
        return Promise.resolve({
          content: "Plugin not available",
          toolCalls: undefined,
        });
      });

      // Even though bridge says not connected, tools are built when bridge exists
      // The execution path handles disconnection gracefully
      const req = makeRequest({ message: "read page" });
      const res = await handleAgentChat(
        req,
        testConfig,
        disconnectedBridge,
        store
      );
      expect(res.status).toBe(200);
    });
  });

  // ── Configurable Message Limit ────────────────────────────────────────────
  describe("configurable message limit", () => {
    test("respects sessionMessageLimit from config", async () => {
      const session = seedTestSession(db, "agent-chat", "Limit Session");
      // Seed many messages
      for (let i = 0; i < 10; i++) {
        seedTestSessionMessage(db, session.id, "user", `msg ${i}`);
        seedTestSessionMessage(db, session.id, "assistant", `reply ${i}`);
      }

      const limitedConfig = { ...testConfig, sessionMessageLimit: 5 };
      const req = makeRequest({
        message: "latest",
        sessionId: session.id,
      });

      await handleAgentChat(req, limitedConfig, bridge, store);

      // Check that chatCompletion was called with limited history + system + new user msg
      const callArgs = mockChatCompletion.mock.calls[0];
      const messages = callArgs[0] as Array<{ role: string; content: string }>;
      // system prompt + up to 5 history messages + 1 new user message
      // The limit applies to history loaded from DB
      const nonSystemMessages = messages.filter((m) => m.role !== "system");
      // 5 history + 1 new user = 6 non-system messages
      expect(nonSystemMessages.length).toBe(6);
    });
  });

  // ── Error Handling ────────────────────────────────────────────────────────
  describe("error handling", () => {
    test("returns 504 on LLM timeout", async () => {
      mockChatCompletion.mockImplementation(() => {
        const err = new Error("timeout");
        err.name = "AbortError";
        return Promise.reject(err);
      });

      const req = makeRequest({ message: "hello" });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      expect(res.status).toBe(504);
    });

    test("returns 500 on generic LLM error", async () => {
      mockChatCompletion.mockImplementation(() =>
        Promise.reject(new Error("Something went wrong"))
      );

      const req = makeRequest({ message: "hello" });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      expect(res.status).toBe(500);
      const body = await res.json();
      expect(body.error).toContain("Something went wrong");
    });
  });

  // ── Response Shape ────────────────────────────────────────────────────────
  describe("response shape", () => {
    test("returns sessionId, response, and optional actions", async () => {
      const req = makeRequest({ message: "hi" });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      const body = await res.json();
      expect(body.success).toBe(true);
      expect(body.data).toHaveProperty("sessionId");
      expect(body.data).toHaveProperty("response");
      // actions should be omitted when empty
      expect(body.data.actions).toBeUndefined();
    });

    test("includes conversationId alias for backward compat", async () => {
      const req = makeRequest({ message: "hi" });
      const res = await handleAgentChat(req, testConfig, bridge, store);
      const body = await res.json();
      // Both sessionId and conversationId in response
      expect(body.data.sessionId).toBeDefined();
      expect(body.data.conversationId).toBe(body.data.sessionId);
    });
  });

  // ── LLM Messages Format ──────────────────────────────────────────────────
  describe("LLM message format", () => {
    test("passes messages to chatCompletion with correct format", async () => {
      const session = seedTestSession(db, "agent-chat", "Format Session");
      // Seed some history with tool calls
      seedTestSessionMessage(db, session.id, "user", "earlier question");
      // Add message with tool_calls directly via store
      store.addMessage({
        session_id: session.id,
        role: "assistant",
        content: "",
        tool_calls: [{ id: "tc_1", function: { name: "page_read", arguments: '{"name":"test"}' } }],
      });

      const req = makeRequest({ message: "follow up", sessionId: session.id });
      await handleAgentChat(req, testConfig, bridge, store);

      const callArgs = mockChatCompletion.mock.calls[0];
      const messages = callArgs[0] as Array<{
        role: string;
        content: string;
        toolCallId?: string;
        toolCalls?: any[];
      }>;

      // Should have: system, user(earlier), assistant(tool_calls), user(follow up)
      expect(messages[0].role).toBe("system");

      // History messages should map tool_call_id -> toolCallId, tool_calls -> toolCalls
      const assistantMsg = messages.find(
        (m) => m.role === "assistant" && m.toolCalls
      );
      expect(assistantMsg).toBeDefined();
      expect(assistantMsg!.toolCalls![0].id).toBe("tc_1");
    });
  });
});
