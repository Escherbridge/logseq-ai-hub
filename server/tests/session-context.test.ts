import { describe, it, expect } from "bun:test";
import type { Session, SessionContext, SessionMessage, WorkingMemoryEntry } from "../src/types/session";
import {
  mergeSessionContext,
  addWorkingMemory,
  removeWorkingMemory,
  addRelevantPage,
  removeRelevantPage,
  buildSessionSystemPrompt,
  resolveRelevantPages,
  summarizeMessages,
} from "../src/services/session-context";
import type { AgentBridge } from "../src/services/agent-bridge";

describe("mergeSessionContext", () => {
  it("should replace focus when provided", () => {
    const existing: SessionContext = { focus: "old focus" };
    const updates: SessionContext = { focus: "new focus" };
    const result = mergeSessionContext(existing, updates);
    expect(result.focus).toBe("new focus");
  });

  it("should preserve focus when not provided in updates", () => {
    const existing: SessionContext = { focus: "existing" };
    const updates: SessionContext = {};
    const result = mergeSessionContext(existing, updates);
    expect(result.focus).toBe("existing");
  });

  it("should union relevant_pages and deduplicate", () => {
    const existing: SessionContext = { relevant_pages: ["A", "B"] };
    const updates: SessionContext = { relevant_pages: ["B", "C"] };
    const result = mergeSessionContext(existing, updates);
    expect(result.relevant_pages).toEqual(["A", "B", "C"]);
  });

  it("should add relevant_pages when existing has none", () => {
    const existing: SessionContext = {};
    const updates: SessionContext = { relevant_pages: ["X", "Y"] };
    const result = mergeSessionContext(existing, updates);
    expect(result.relevant_pages).toEqual(["X", "Y"]);
  });

  it("should deduplicate relevant_pages case-insensitively", () => {
    const existing: SessionContext = { relevant_pages: ["PageA"] };
    const updates: SessionContext = { relevant_pages: ["pagea", "PageB"] };
    const result = mergeSessionContext(existing, updates);
    // Keeps the existing casing, adds new ones
    expect(result.relevant_pages).toHaveLength(2);
    expect(result.relevant_pages).toContain("PageA");
    expect(result.relevant_pages).toContain("PageB");
  });

  it("should merge working_memory by key (update existing)", () => {
    const existing: SessionContext = {
      working_memory: [
        { key: "a", value: "1", addedAt: "2025-01-01T00:00:00Z" },
      ],
    };
    const updates: SessionContext = {
      working_memory: [
        { key: "a", value: "2", addedAt: "2025-01-02T00:00:00Z" },
      ],
    };
    const result = mergeSessionContext(existing, updates);
    expect(result.working_memory).toHaveLength(1);
    expect(result.working_memory![0].key).toBe("a");
    expect(result.working_memory![0].value).toBe("2");
    expect(result.working_memory![0].addedAt).toBe("2025-01-02T00:00:00Z");
  });

  it("should merge working_memory by key (add new)", () => {
    const existing: SessionContext = {
      working_memory: [
        { key: "a", value: "1", addedAt: "2025-01-01T00:00:00Z" },
      ],
    };
    const updates: SessionContext = {
      working_memory: [
        { key: "b", value: "2", addedAt: "2025-01-02T00:00:00Z" },
      ],
    };
    const result = mergeSessionContext(existing, updates);
    expect(result.working_memory).toHaveLength(2);
    expect(result.working_memory!.find((e) => e.key === "a")).toBeDefined();
    expect(result.working_memory!.find((e) => e.key === "b")).toBeDefined();
  });

  it("should merge preferences shallowly", () => {
    const existing: SessionContext = {
      preferences: { verbosity: "concise" },
    };
    const updates: SessionContext = {
      preferences: { auto_approve: true },
    };
    const result = mergeSessionContext(existing, updates);
    expect(result.preferences).toEqual({
      verbosity: "concise",
      auto_approve: true,
    });
  });

  it("should preserve existing values when merging with empty/undefined fields", () => {
    const existing: SessionContext = {
      focus: "test",
      relevant_pages: ["A"],
      working_memory: [{ key: "x", value: "1", addedAt: "2025-01-01T00:00:00Z" }],
      preferences: { verbosity: "verbose" },
    };
    const updates: SessionContext = {};
    const result = mergeSessionContext(existing, updates);
    expect(result.focus).toBe("test");
    expect(result.relevant_pages).toEqual(["A"]);
    expect(result.working_memory).toHaveLength(1);
    expect(result.preferences).toEqual({ verbosity: "verbose" });
  });

  it("should handle merging two empty contexts", () => {
    const result = mergeSessionContext({}, {});
    expect(result).toEqual({});
  });

  it("should handle merging into empty existing context", () => {
    const updates: SessionContext = {
      focus: "new",
      relevant_pages: ["P1"],
      working_memory: [{ key: "k", value: "v", addedAt: "2025-01-01T00:00:00Z" }],
      preferences: { verbosity: "concise" },
    };
    const result = mergeSessionContext({}, updates);
    expect(result.focus).toBe("new");
    expect(result.relevant_pages).toEqual(["P1"]);
    expect(result.working_memory).toHaveLength(1);
    expect(result.preferences).toEqual({ verbosity: "concise" });
  });
});

describe("addWorkingMemory", () => {
  it("should add a new entry with addedAt timestamp", () => {
    const ctx: SessionContext = {};
    const result = addWorkingMemory(ctx, "branch", "main");
    expect(result.working_memory).toHaveLength(1);
    expect(result.working_memory![0].key).toBe("branch");
    expect(result.working_memory![0].value).toBe("main");
    expect(result.working_memory![0].addedAt).toBeDefined();
    // addedAt should be a valid ISO date
    expect(new Date(result.working_memory![0].addedAt).toISOString()).toBe(
      result.working_memory![0].addedAt
    );
  });

  it("should update an existing entry's value and addedAt", () => {
    const ctx: SessionContext = {
      working_memory: [
        { key: "branch", value: "main", addedAt: "2025-01-01T00:00:00.000Z" },
      ],
    };
    const result = addWorkingMemory(ctx, "branch", "develop");
    expect(result.working_memory).toHaveLength(1);
    expect(result.working_memory![0].value).toBe("develop");
    expect(result.working_memory![0].addedAt).not.toBe("2025-01-01T00:00:00.000Z");
  });

  it("should set source when provided", () => {
    const ctx: SessionContext = {};
    const result = addWorkingMemory(ctx, "key", "val", "auto");
    expect(result.working_memory![0].source).toBe("auto");
  });

  it("should default source to manual when not provided", () => {
    const ctx: SessionContext = {};
    const result = addWorkingMemory(ctx, "key", "val");
    expect(result.working_memory![0].source).toBe("manual");
  });

  it("should evict the oldest entry when at cap of 20", () => {
    const entries: WorkingMemoryEntry[] = [];
    for (let i = 0; i < 20; i++) {
      entries.push({
        key: `key-${i}`,
        value: `val-${i}`,
        addedAt: new Date(2025, 0, 1, 0, 0, i).toISOString(),
      });
    }
    const ctx: SessionContext = { working_memory: entries };
    const result = addWorkingMemory(ctx, "key-new", "val-new");
    expect(result.working_memory).toHaveLength(20);
    // Oldest (key-0) should be evicted
    expect(result.working_memory!.find((e) => e.key === "key-0")).toBeUndefined();
    // New entry should be present
    expect(result.working_memory!.find((e) => e.key === "key-new")).toBeDefined();
  });

  it("should not mutate the original context", () => {
    const ctx: SessionContext = {
      working_memory: [
        { key: "a", value: "1", addedAt: "2025-01-01T00:00:00.000Z" },
      ],
    };
    const result = addWorkingMemory(ctx, "b", "2");
    expect(ctx.working_memory).toHaveLength(1);
    expect(result.working_memory).toHaveLength(2);
  });
});

describe("removeWorkingMemory", () => {
  it("should remove an entry by key", () => {
    const ctx: SessionContext = {
      working_memory: [
        { key: "branch", value: "main", addedAt: "2025-01-01T00:00:00.000Z" },
        { key: "env", value: "prod", addedAt: "2025-01-01T00:00:00.000Z" },
      ],
    };
    const result = removeWorkingMemory(ctx, "branch");
    expect(result.working_memory).toHaveLength(1);
    expect(result.working_memory![0].key).toBe("env");
  });

  it("should return context unchanged if key does not exist", () => {
    const ctx: SessionContext = {
      working_memory: [
        { key: "branch", value: "main", addedAt: "2025-01-01T00:00:00.000Z" },
      ],
    };
    const result = removeWorkingMemory(ctx, "nonexistent");
    expect(result.working_memory).toHaveLength(1);
    expect(result.working_memory![0].key).toBe("branch");
  });

  it("should handle empty working_memory", () => {
    const ctx: SessionContext = {};
    const result = removeWorkingMemory(ctx, "any");
    expect(result.working_memory).toBeUndefined();
  });

  it("should not mutate the original context", () => {
    const ctx: SessionContext = {
      working_memory: [
        { key: "a", value: "1", addedAt: "2025-01-01T00:00:00.000Z" },
      ],
    };
    const result = removeWorkingMemory(ctx, "a");
    expect(ctx.working_memory).toHaveLength(1);
    expect(result.working_memory).toHaveLength(0);
  });
});

describe("addRelevantPage", () => {
  it("should add a page to empty context", () => {
    const ctx: SessionContext = {};
    const result = addRelevantPage(ctx, "Skills/api-deploy");
    expect(result.relevant_pages).toEqual(["Skills/api-deploy"]);
  });

  it("should not add a duplicate (case-insensitive) but move it to end (MRU)", () => {
    const ctx: SessionContext = { relevant_pages: ["PageA", "PageB"] };
    const result = addRelevantPage(ctx, "pagea");
    // PageA should be moved to end with the new casing
    expect(result.relevant_pages).toHaveLength(2);
    expect(result.relevant_pages![0]).toBe("PageB");
    expect(result.relevant_pages![1]).toBe("pagea");
  });

  it("should evict the oldest (first) when at cap of 10", () => {
    const pages = Array.from({ length: 10 }, (_, i) => `Page${i}`);
    const ctx: SessionContext = { relevant_pages: pages };
    const result = addRelevantPage(ctx, "NewPage");
    expect(result.relevant_pages).toHaveLength(10);
    // Page0 (first/oldest) should be evicted
    expect(result.relevant_pages).not.toContain("Page0");
    // NewPage should be at the end
    expect(result.relevant_pages![result.relevant_pages!.length - 1]).toBe("NewPage");
  });

  it("should store page names as-provided (not lowercased)", () => {
    const ctx: SessionContext = {};
    const result = addRelevantPage(ctx, "My Custom Page");
    expect(result.relevant_pages![0]).toBe("My Custom Page");
  });

  it("should not mutate the original context", () => {
    const ctx: SessionContext = { relevant_pages: ["A"] };
    const result = addRelevantPage(ctx, "B");
    expect(ctx.relevant_pages).toHaveLength(1);
    expect(result.relevant_pages).toHaveLength(2);
  });
});

describe("removeRelevantPage", () => {
  it("should remove a page by name (case-insensitive)", () => {
    const ctx: SessionContext = { relevant_pages: ["Skills/api-deploy", "Notes"] };
    const result = removeRelevantPage(ctx, "skills/api-deploy");
    expect(result.relevant_pages).toEqual(["Notes"]);
  });

  it("should return context unchanged if page not found", () => {
    const ctx: SessionContext = { relevant_pages: ["A"] };
    const result = removeRelevantPage(ctx, "nonexistent");
    expect(result.relevant_pages).toEqual(["A"]);
  });

  it("should handle empty relevant_pages", () => {
    const ctx: SessionContext = {};
    const result = removeRelevantPage(ctx, "any");
    expect(result.relevant_pages).toBeUndefined();
  });

  it("should not mutate the original context", () => {
    const ctx: SessionContext = { relevant_pages: ["A", "B"] };
    const result = removeRelevantPage(ctx, "A");
    expect(ctx.relevant_pages).toHaveLength(2);
    expect(result.relevant_pages).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// buildSessionSystemPrompt
// ---------------------------------------------------------------------------

function makeSession(ctx: SessionContext = {}): Session {
  return {
    id: "test-session-id",
    name: "Test Session",
    agent_id: "claude-code",
    status: "active",
    context: ctx,
    created_at: "2025-01-01T00:00:00Z",
    updated_at: "2025-01-01T00:00:00Z",
    last_active_at: "2025-01-01T00:00:00Z",
  };
}

describe("buildSessionSystemPrompt", () => {
  it("should produce the base system prompt when session has no context", () => {
    const session = makeSession({});
    const prompt = buildSessionSystemPrompt(session);
    // Should contain the base agent prompt (from agent.ts)
    expect(prompt).toContain("Logseq AI Hub");
    // Should NOT contain session-specific sections
    expect(prompt).not.toContain("Current Focus");
    expect(prompt).not.toContain("Working Memory");
    expect(prompt).not.toContain("Relevant Pages");
  });

  it("should include a Current Focus section when focus is set", () => {
    const session = makeSession({ focus: "deploying API" });
    const prompt = buildSessionSystemPrompt(session);
    expect(prompt).toContain("Current Focus");
    expect(prompt).toContain("deploying API");
  });

  it("should include a Working Memory section with key-value pairs", () => {
    const session = makeSession({
      working_memory: [
        { key: "branch", value: "feature/auth", addedAt: "2025-01-01T00:00:00Z" },
        { key: "env", value: "staging", addedAt: "2025-01-01T00:00:00Z" },
      ],
    });
    const prompt = buildSessionSystemPrompt(session);
    expect(prompt).toContain("Working Memory");
    expect(prompt).toContain("branch");
    expect(prompt).toContain("feature/auth");
    expect(prompt).toContain("env");
    expect(prompt).toContain("staging");
  });

  it("should include Relevant Pages section with page content when provided", () => {
    const session = makeSession({
      relevant_pages: ["Skills/api-deploy", "Notes/design"],
    });
    const pageContents = new Map([
      ["Skills/api-deploy", "Deploy skill content here..."],
      ["Notes/design", "Design notes content..."],
    ]);
    const prompt = buildSessionSystemPrompt(session, pageContents);
    expect(prompt).toContain("Relevant Pages");
    expect(prompt).toContain("Skills/api-deploy");
    expect(prompt).toContain("Deploy skill content here...");
    expect(prompt).toContain("Notes/design");
    expect(prompt).toContain("Design notes content...");
  });

  it("should truncate long page content", () => {
    const session = makeSession({
      relevant_pages: ["LongPage"],
    });
    const longContent = "x".repeat(5000);
    const pageContents = new Map([["LongPage", longContent]]);
    const prompt = buildSessionSystemPrompt(session, pageContents);
    // Should be present but truncated (max 4000 chars per page)
    expect(prompt).toContain("LongPage");
    // The full 5000-char content should NOT appear (truncated to 4000)
    expect(prompt).not.toContain(longContent);
    expect(prompt).toContain("(truncated)");
  });

  it("should handle relevant pages without page content (pages not resolved)", () => {
    const session = makeSession({
      relevant_pages: ["Skills/api-deploy"],
    });
    // No pageContents provided
    const prompt = buildSessionSystemPrompt(session);
    // Should list pages but without content
    expect(prompt).toContain("Relevant Pages");
    expect(prompt).toContain("Skills/api-deploy");
  });

  it("should include session name in prompt when available", () => {
    const session = makeSession({ focus: "test" });
    session.name = "API Refactor Session";
    const prompt = buildSessionSystemPrompt(session);
    expect(prompt).toContain("API Refactor Session");
  });

  it("should be well-structured with all sections present", () => {
    const session = makeSession({
      focus: "testing",
      working_memory: [
        { key: "branch", value: "main", addedAt: "2025-01-01T00:00:00Z" },
      ],
      relevant_pages: ["DesignDoc"],
    });
    const pageContents = new Map([["DesignDoc", "Design document content"]]);
    const prompt = buildSessionSystemPrompt(session, pageContents);
    // All sections should be present and in order
    const focusIdx = prompt.indexOf("Current Focus");
    const memoryIdx = prompt.indexOf("Working Memory");
    const pagesIdx = prompt.indexOf("Relevant Pages");
    expect(focusIdx).toBeGreaterThan(-1);
    expect(memoryIdx).toBeGreaterThan(focusIdx);
    expect(pagesIdx).toBeGreaterThan(memoryIdx);
  });
});

// ---------------------------------------------------------------------------
// resolveRelevantPages
// ---------------------------------------------------------------------------

function makeMockBridge(
  responses: Record<string, unknown>,
  connected = true
): AgentBridge {
  return {
    isPluginConnected: () => connected,
    sendRequest: async (operation: string, params: Record<string, unknown>) => {
      const pageName = params.name as string;
      if (responses[pageName] !== undefined) {
        return responses[pageName];
      }
      throw new Error(`Page not found: ${pageName}`);
    },
  } as unknown as AgentBridge;
}

describe("resolveRelevantPages", () => {
  it("should resolve a single page name to its content", async () => {
    const bridge = makeMockBridge({ "Skills/api": "API skill content" });
    const result = await resolveRelevantPages(bridge, ["Skills/api"]);
    expect(result.get("Skills/api")).toBe("API skill content");
  });

  it("should resolve multiple pages in parallel", async () => {
    const bridge = makeMockBridge({
      "Page1": "Content 1",
      "Page2": "Content 2",
      "Page3": "Content 3",
    });
    const result = await resolveRelevantPages(bridge, ["Page1", "Page2", "Page3"]);
    expect(result.size).toBe(3);
    expect(result.get("Page1")).toBe("Content 1");
    expect(result.get("Page2")).toBe("Content 2");
    expect(result.get("Page3")).toBe("Content 3");
  });

  it("should skip pages that fail to load", async () => {
    const bridge = makeMockBridge({ "Page1": "Content 1" });
    // Page2 is not in responses, so sendRequest will throw
    const result = await resolveRelevantPages(bridge, ["Page1", "Page2"]);
    expect(result.size).toBe(1);
    expect(result.get("Page1")).toBe("Content 1");
    expect(result.has("Page2")).toBe(false);
  });

  it("should return empty map if bridge is not connected", async () => {
    const bridge = makeMockBridge({}, false);
    const result = await resolveRelevantPages(bridge, ["Page1"]);
    expect(result.size).toBe(0);
  });

  it("should return empty map for empty page list", async () => {
    const bridge = makeMockBridge({});
    const result = await resolveRelevantPages(bridge, []);
    expect(result.size).toBe(0);
  });

  it("should convert non-string results to string", async () => {
    const bridge = makeMockBridge({
      "Page1": { blocks: [{ content: "block 1" }] },
    });
    const result = await resolveRelevantPages(bridge, ["Page1"]);
    expect(result.size).toBe(1);
    expect(typeof result.get("Page1")).toBe("string");
  });
});

// ---------------------------------------------------------------------------
// summarizeMessages
// ---------------------------------------------------------------------------

function makeSessionMessage(
  id: number,
  role: SessionMessage["role"],
  content: string
): SessionMessage {
  return {
    id,
    session_id: "test-session",
    role,
    content,
    tool_calls: null,
    tool_call_id: null,
    metadata: null,
    created_at: `2025-01-01T00:00:0${id % 10}.000Z`,
  };
}

describe("summarizeMessages", () => {
  it("should return summary and original message count", async () => {
    const messages: SessionMessage[] = Array.from({ length: 5 }, (_, i) =>
      makeSessionMessage(i + 1, i % 2 === 0 ? "user" : "assistant", `Message ${i + 1}`)
    );

    const mockLlmCall = async (_msgs: any[], _model: string): Promise<string> => {
      return "Summary of the conversation.";
    };

    const result = await summarizeMessages(messages, mockLlmCall);
    expect(result.summary).toBe("Summary of the conversation.");
    expect(result.originalMessageCount).toBe(5);
  });

  it("should format messages as readable text for the LLM", async () => {
    const messages: SessionMessage[] = [
      makeSessionMessage(1, "user", "Hello"),
      makeSessionMessage(2, "assistant", "Hi there"),
    ];

    let capturedMessages: any[] = [];
    const mockLlmCall = async (msgs: any[], _model: string): Promise<string> => {
      capturedMessages = msgs;
      return "Summary.";
    };

    await summarizeMessages(messages, mockLlmCall);
    expect(capturedMessages.length).toBeGreaterThan(0);
    const combinedContent = capturedMessages.map((m: any) => m.content).join(" ");
    expect(combinedContent).toContain("Hello");
    expect(combinedContent).toContain("Hi there");
  });

  it("should use the provided model (haiku)", async () => {
    const messages: SessionMessage[] = [
      makeSessionMessage(1, "user", "Test"),
    ];

    let capturedModel = "";
    const mockLlmCall = async (_msgs: any[], model: string): Promise<string> => {
      capturedModel = model;
      return "Summary.";
    };

    await summarizeMessages(messages, mockLlmCall, "anthropic/claude-haiku-4-5-20251001");
    expect(capturedModel).toBe("anthropic/claude-haiku-4-5-20251001");
  });

  it("should use default haiku model when none specified", async () => {
    const messages: SessionMessage[] = [
      makeSessionMessage(1, "user", "Test"),
    ];

    let capturedModel = "";
    const mockLlmCall = async (_msgs: any[], model: string): Promise<string> => {
      capturedModel = model;
      return "Summary.";
    };

    await summarizeMessages(messages, mockLlmCall);
    expect(capturedModel).toContain("haiku");
  });

  it("should handle empty message list", async () => {
    const mockLlmCall = async (_msgs: any[], _model: string): Promise<string> => {
      return "No messages.";
    };

    const result = await summarizeMessages([], mockLlmCall);
    expect(result.originalMessageCount).toBe(0);
    expect(result.summary).toBe("No messages.");
  });

  it("should skip system messages when formatting for LLM", async () => {
    const messages: SessionMessage[] = [
      makeSessionMessage(1, "system", "System prompt"),
      makeSessionMessage(2, "user", "User question"),
      makeSessionMessage(3, "assistant", "Assistant answer"),
    ];

    let capturedMessages: any[] = [];
    const mockLlmCall = async (msgs: any[], _model: string): Promise<string> => {
      capturedMessages = msgs;
      return "Summary.";
    };

    await summarizeMessages(messages, mockLlmCall);
    const userMsg = capturedMessages.find((m: any) => m.role === "user");
    expect(userMsg).toBeDefined();
    // System prompt content should not appear in the transcript
    expect(userMsg.content).not.toContain("System prompt");
    expect(userMsg.content).toContain("User question");
  });

  it("should return originalMessageCount equal to input length", async () => {
    const messages: SessionMessage[] = Array.from({ length: 30 }, (_, i) =>
      makeSessionMessage(i + 1, i % 2 === 0 ? "user" : "assistant", `Turn ${i + 1}`)
    );

    const mockLlmCall = async (_msgs: any[], _model: string): Promise<string> => {
      return "Summarized 30 messages.";
    };

    const result = await summarizeMessages(messages, mockLlmCall);
    expect(result.originalMessageCount).toBe(30);
  });
});
