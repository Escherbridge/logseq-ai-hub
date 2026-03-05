import { describe, it, expect, beforeEach } from "bun:test";
import { Database } from "bun:sqlite";
import { createTestDb, seedTestSession, seedTestSessionMessage } from "./helpers";
import { SessionStore } from "../src/services/session-store";
import {
  autoEnrichContext,
  operationToAutoContextEvent,
  summarizeMessages,
} from "../src/services/session-context";
import type { SessionContext, SessionMessage } from "../src/types/session";
import type { AutoContextEvent } from "../src/services/session-context";

// ---------------------------------------------------------------------------
// autoEnrichContext
// ---------------------------------------------------------------------------

describe("autoEnrichContext", () => {
  it("page_modified adds page to relevant_pages", () => {
    const ctx: SessionContext = {};
    const event: AutoContextEvent = { type: "page_modified", pageName: "Skills/deploy" };
    const result = autoEnrichContext(ctx, event);
    expect(result.relevant_pages).toContain("Skills/deploy");
  });

  it("page_modified without pageName returns unchanged context", () => {
    const ctx: SessionContext = { relevant_pages: ["Existing"] };
    const event: AutoContextEvent = { type: "page_modified" };
    const result = autoEnrichContext(ctx, event);
    expect(result).toBe(ctx);
  });

  it("page_modified respects cap of 10 pages", () => {
    const pages = Array.from({ length: 10 }, (_, i) => `Page${i}`);
    const ctx: SessionContext = { relevant_pages: pages };
    const event: AutoContextEvent = { type: "page_modified", pageName: "NewPage" };
    const result = autoEnrichContext(ctx, event);
    expect(result.relevant_pages).toHaveLength(10);
    expect(result.relevant_pages).not.toContain("Page0");
    expect(result.relevant_pages).toContain("NewPage");
  });

  it("job_created adds to working_memory with job: prefix", () => {
    const ctx: SessionContext = {};
    const event: AutoContextEvent = {
      type: "job_created",
      jobId: "abc123",
      jobName: "Deploy API",
    };
    const result = autoEnrichContext(ctx, event);
    expect(result.working_memory).toHaveLength(1);
    expect(result.working_memory![0].key).toBe("job:abc123");
    expect(result.working_memory![0].value).toContain("Deploy API");
    expect(result.working_memory![0].value).toContain("created");
  });

  it("job_created without jobId returns unchanged context", () => {
    const ctx: SessionContext = {};
    const event: AutoContextEvent = { type: "job_created" };
    const result = autoEnrichContext(ctx, event);
    expect(result).toBe(ctx);
  });

  it("job_created uses jobId as fallback when jobName is missing", () => {
    const ctx: SessionContext = {};
    const event: AutoContextEvent = { type: "job_created", jobId: "xyz" };
    const result = autoEnrichContext(ctx, event);
    expect(result.working_memory![0].value).toContain("xyz");
  });

  it("approval_pending adds to working_memory with approval: prefix and pending: value", () => {
    const ctx: SessionContext = {};
    const event: AutoContextEvent = {
      type: "approval_pending",
      approvalId: "appr-1",
      question: "Allow file deletion?",
    };
    const result = autoEnrichContext(ctx, event);
    expect(result.working_memory).toHaveLength(1);
    expect(result.working_memory![0].key).toBe("approval:appr-1");
    expect(result.working_memory![0].value).toContain("pending:");
    expect(result.working_memory![0].value).toContain("Allow file deletion?");
  });

  it("approval_pending without approvalId returns unchanged context", () => {
    const ctx: SessionContext = {};
    const event: AutoContextEvent = { type: "approval_pending" };
    const result = autoEnrichContext(ctx, event);
    expect(result).toBe(ctx);
  });

  it("approval_pending uses fallback question when not provided", () => {
    const ctx: SessionContext = {};
    const event: AutoContextEvent = { type: "approval_pending", approvalId: "appr-2" };
    const result = autoEnrichContext(ctx, event);
    expect(result.working_memory![0].value).toContain("pending:");
    expect(result.working_memory![0].value).toContain("awaiting approval");
  });

  it("approval_resolved updates existing approval entry with resolved: value", () => {
    const ctx: SessionContext = {
      working_memory: [
        {
          key: "approval:appr-1",
          value: "pending: Allow file deletion?",
          addedAt: "2025-01-01T00:00:00Z",
          source: "auto",
        },
      ],
    };
    const event: AutoContextEvent = {
      type: "approval_resolved",
      approvalId: "appr-1",
      result: "denied by user",
    };
    const result = autoEnrichContext(ctx, event);
    expect(result.working_memory).toHaveLength(1);
    expect(result.working_memory![0].key).toBe("approval:appr-1");
    expect(result.working_memory![0].value).toContain("resolved:");
    expect(result.working_memory![0].value).toContain("denied by user");
  });

  it("approval_resolved without approvalId returns unchanged context", () => {
    const ctx: SessionContext = {};
    const event: AutoContextEvent = { type: "approval_resolved" };
    const result = autoEnrichContext(ctx, event);
    expect(result).toBe(ctx);
  });

  it("approval_resolved uses fallback result when not provided", () => {
    const ctx: SessionContext = {};
    const event: AutoContextEvent = { type: "approval_resolved", approvalId: "appr-3" };
    const result = autoEnrichContext(ctx, event);
    expect(result.working_memory![0].value).toContain("resolved:");
    expect(result.working_memory![0].value).toContain("approved");
  });

  it("auto-added items have source: auto", () => {
    const ctx: SessionContext = {};
    const jobEvent: AutoContextEvent = { type: "job_created", jobId: "j1", jobName: "Job" };
    const result = autoEnrichContext(ctx, jobEvent);
    expect(result.working_memory![0].source).toBe("auto");
  });

  it("multiple events handled correctly in sequence", () => {
    let ctx: SessionContext = {};

    ctx = autoEnrichContext(ctx, { type: "page_modified", pageName: "PageA" });
    ctx = autoEnrichContext(ctx, { type: "job_created", jobId: "j1", jobName: "Build" });
    ctx = autoEnrichContext(ctx, { type: "approval_pending", approvalId: "a1", question: "OK?" });
    ctx = autoEnrichContext(ctx, { type: "approval_resolved", approvalId: "a1", result: "yes" });

    expect(ctx.relevant_pages).toContain("PageA");
    expect(ctx.working_memory).toHaveLength(2); // job + approval (resolved replaces pending)
    const jobEntry = ctx.working_memory!.find((e) => e.key === "job:j1");
    expect(jobEntry).toBeDefined();
    expect(jobEntry!.value).toContain("Build");
    const approvalEntry = ctx.working_memory!.find((e) => e.key === "approval:a1");
    expect(approvalEntry).toBeDefined();
    expect(approvalEntry!.value).toContain("resolved:");
  });

  it("unknown event type returns context unchanged", () => {
    const ctx: SessionContext = { focus: "testing" };
    const event = { type: "unknown_event" } as unknown as AutoContextEvent;
    const result = autoEnrichContext(ctx, event);
    expect(result).toBe(ctx);
  });
});

// ---------------------------------------------------------------------------
// operationToAutoContextEvent
// ---------------------------------------------------------------------------

describe("operationToAutoContextEvent", () => {
  it("page_create maps to page_modified", () => {
    const event = operationToAutoContextEvent("page_create", { name: "NewPage" });
    expect(event).not.toBeNull();
    expect(event!.type).toBe("page_modified");
    expect(event!.pageName).toBe("NewPage");
  });

  it("page_read maps to page_modified", () => {
    const event = operationToAutoContextEvent("page_read", { name: "ReadPage" });
    expect(event).not.toBeNull();
    expect(event!.type).toBe("page_modified");
    expect(event!.pageName).toBe("ReadPage");
  });

  it("block_append maps to page_modified with page from args", () => {
    const event = operationToAutoContextEvent("block_append", { page: "TargetPage", content: "text" });
    expect(event).not.toBeNull();
    expect(event!.type).toBe("page_modified");
    expect(event!.pageName).toBe("TargetPage");
  });

  it("create_job maps to job_created", () => {
    const event = operationToAutoContextEvent("create_job", { name: "deploy-task" });
    expect(event).not.toBeNull();
    expect(event!.type).toBe("job_created");
    expect(event!.jobId).toBe("deploy-task");
    expect(event!.jobName).toBe("deploy-task");
  });

  it("graph_search (non-modifying) returns null", () => {
    const event = operationToAutoContextEvent("graph_search", { query: "test" });
    expect(event).toBeNull();
  });

  it("memory_store (non-modifying) returns null", () => {
    const event = operationToAutoContextEvent("memory_store", { key: "k", value: "v" });
    expect(event).toBeNull();
  });

  it("unknown operation returns null", () => {
    const event = operationToAutoContextEvent("something_random", {});
    expect(event).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// summarizeMessages integration
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
    created_at: `2025-01-01T00:00:${String(id % 60).padStart(2, "0")}.000Z`,
  };
}

describe("summarizeMessages integration", () => {
  let db: Database;
  let store: SessionStore;

  beforeEach(() => {
    db = createTestDb();
    store = new SessionStore(db);
  });

  it("summarizeMessages produces a summary with correct message count for many messages", async () => {
    const session = seedTestSession(db);
    const messages: SessionMessage[] = [];
    for (let i = 0; i < 60; i++) {
      const role: SessionMessage["role"] = i % 2 === 0 ? "user" : "assistant";
      const msg = seedTestSessionMessage(db, session.id, role, `Turn ${i + 1}: discussing topic ${i}`);
      messages.push(msg);
    }

    const mockLlmCall = async (msgs: any[], _model: string): Promise<string> => {
      // Simulate a real summary
      const userMsg = msgs.find((m: any) => m.role === "user");
      const turnCount = (userMsg?.content?.match(/Turn/g) || []).length;
      return `Summary of ${turnCount} turns discussing various topics. Key decisions included reviewing deployment and configuration.`;
    };

    const result = await summarizeMessages(messages, mockLlmCall);
    expect(result.originalMessageCount).toBe(60);
    expect(result.summary).toContain("Summary");
    expect(result.summary.length).toBeGreaterThan(0);
  });

  it("summary + recent messages format works for LLM consumption", async () => {
    // Simulate the full flow: summarize old messages, then combine with recent
    const allMessages: SessionMessage[] = Array.from({ length: 60 }, (_, i) =>
      makeSessionMessage(
        i + 1,
        i % 2 === 0 ? "user" : "assistant",
        `Turn ${i + 1}`
      )
    );

    const oldMessages = allMessages.slice(0, 40);
    const recentMessages = allMessages.slice(40);

    const mockLlmCall = async (_msgs: any[], _model: string): Promise<string> => {
      return "The conversation covered turns 1-40 with discussions about project setup and configuration.";
    };

    const { summary, originalMessageCount } = await summarizeMessages(oldMessages, mockLlmCall);
    expect(originalMessageCount).toBe(40);

    // Build the messages array that would be sent to the LLM
    const llmMessages: Array<{ role: string; content: string }> = [
      {
        role: "system",
        content: `Previous conversation summary (${originalMessageCount} messages):\n${summary}`,
      },
      ...recentMessages.map((m) => ({ role: m.role, content: m.content })),
    ];

    // Verify structure is suitable for LLM consumption
    expect(llmMessages[0].role).toBe("system");
    expect(llmMessages[0].content).toContain("Previous conversation summary");
    expect(llmMessages[0].content).toContain("40 messages");
    expect(llmMessages.length).toBe(21); // 1 system + 20 recent
    expect(llmMessages[1].content).toBe("Turn 41");
    expect(llmMessages[llmMessages.length - 1].content).toBe("Turn 60");
  });

  it("empty messages produce a fallback from the LLM", async () => {
    let capturedUserContent = "";
    const mockLlmCall = async (msgs: any[], _model: string): Promise<string> => {
      const userMsg = msgs.find((m: any) => m.role === "user");
      capturedUserContent = userMsg?.content || "";
      return "No significant conversation to summarize.";
    };

    const result = await summarizeMessages([], mockLlmCall);
    expect(result.originalMessageCount).toBe(0);
    expect(result.summary).toBe("No significant conversation to summarize.");
    // The function should send a fallback transcript
    expect(capturedUserContent).toContain("no conversation");
  });

  it("summarizeMessages with real session store messages", async () => {
    const session = seedTestSession(db);

    // Seed realistic conversation
    seedTestSessionMessage(db, session.id, "user", "What pages do I have about deployment?");
    seedTestSessionMessage(db, session.id, "assistant", "I found 3 pages related to deployment.");
    seedTestSessionMessage(db, session.id, "user", "Show me the deploy skill page.");
    seedTestSessionMessage(db, session.id, "assistant", "Here is the Skills/deploy page content...");
    seedTestSessionMessage(db, session.id, "user", "Update the deployment target to production.");
    seedTestSessionMessage(db, session.id, "assistant", "Done, I updated the deployment target.");

    const storedMessages = store.getMessages(session.id, { limit: 100 });
    expect(storedMessages.length).toBe(6);

    const mockLlmCall = async (_msgs: any[], _model: string): Promise<string> => {
      return "User reviewed deployment pages and updated the target to production.";
    };

    const result = await summarizeMessages(storedMessages, mockLlmCall);
    expect(result.originalMessageCount).toBe(6);
    expect(result.summary).toContain("deployment");
  });
});
