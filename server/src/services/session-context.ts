import type {
  Session,
  SessionContext,
  SessionMessage,
  WorkingMemoryEntry,
} from "../types/session";
import { buildSystemPrompt } from "./agent";
import type { AgentBridge } from "./agent-bridge";

/**
 * Deep-merge two SessionContext objects.
 * - `focus`: replaced if provided in updates
 * - `relevant_pages`: unioned with case-insensitive deduplication
 * - `working_memory`: merged by key (update existing, add new)
 * - `preferences`: shallow-merged
 */
export function mergeSessionContext(
  existing: SessionContext,
  updates: SessionContext
): SessionContext {
  const result: SessionContext = {};

  // Focus: replace if provided, otherwise keep existing
  const focus = updates.focus !== undefined ? updates.focus : existing.focus;
  if (focus !== undefined) {
    result.focus = focus;
  }

  // Relevant pages: union with case-insensitive deduplication
  if (existing.relevant_pages || updates.relevant_pages) {
    const existingPages = existing.relevant_pages ?? [];
    const updatePages = updates.relevant_pages ?? [];
    const seen = new Set<string>(existingPages.map((p) => p.toLowerCase()));
    const merged = [...existingPages];
    for (const page of updatePages) {
      if (!seen.has(page.toLowerCase())) {
        merged.push(page);
        seen.add(page.toLowerCase());
      }
    }
    result.relevant_pages = merged;
  }

  // Working memory: merge by key
  if (existing.working_memory || updates.working_memory) {
    const existingEntries = existing.working_memory ?? [];
    const updateEntries = updates.working_memory ?? [];
    const byKey = new Map<string, WorkingMemoryEntry>();
    for (const entry of existingEntries) {
      byKey.set(entry.key, { ...entry });
    }
    for (const entry of updateEntries) {
      byKey.set(entry.key, { ...entry });
    }
    result.working_memory = Array.from(byKey.values());
  }

  // Preferences: shallow merge
  if (existing.preferences || updates.preferences) {
    result.preferences = {
      ...existing.preferences,
      ...updates.preferences,
    };
  }

  return result;
}

// ---------------------------------------------------------------------------
// Working Memory Helpers
// ---------------------------------------------------------------------------

const WORKING_MEMORY_CAP = 20;

/**
 * Add or update a key-value entry in the session's working memory.
 * If the key already exists, its value and addedAt are updated.
 * Evicts the oldest entry (by addedAt) when at the cap of 20.
 * Returns a new SessionContext (does not mutate the original).
 */
export function addWorkingMemory(
  ctx: SessionContext,
  key: string,
  value: string,
  source: "manual" | "auto" = "manual"
): SessionContext {
  const entries = [...(ctx.working_memory ?? [])];
  const now = new Date().toISOString();
  const existingIdx = entries.findIndex((e) => e.key === key);

  if (existingIdx !== -1) {
    entries[existingIdx] = { key, value, addedAt: now, source };
  } else {
    // Evict oldest if at cap
    if (entries.length >= WORKING_MEMORY_CAP) {
      let oldestIdx = 0;
      for (let i = 1; i < entries.length; i++) {
        if (entries[i].addedAt < entries[oldestIdx].addedAt) {
          oldestIdx = i;
        }
      }
      entries.splice(oldestIdx, 1);
    }
    entries.push({ key, value, addedAt: now, source });
  }

  return { ...ctx, working_memory: entries };
}

/**
 * Remove a key from the session's working memory.
 * Returns the context unchanged if the key does not exist.
 * Returns a new SessionContext (does not mutate the original).
 */
export function removeWorkingMemory(
  ctx: SessionContext,
  key: string
): SessionContext {
  if (!ctx.working_memory) return ctx;
  const filtered = ctx.working_memory.filter((e) => e.key !== key);
  return { ...ctx, working_memory: filtered };
}

// ---------------------------------------------------------------------------
// Relevant Pages Helpers
// ---------------------------------------------------------------------------

const RELEVANT_PAGES_CAP = 10;

/**
 * Add a page to the session's relevant_pages list.
 * Case-insensitive deduplication: if the page already exists (case-insensitive),
 * it is moved to the end (MRU) with the new casing.
 * Evicts the oldest (first in array) when at the cap of 10.
 * Returns a new SessionContext (does not mutate the original).
 */
export function addRelevantPage(
  ctx: SessionContext,
  pageName: string
): SessionContext {
  const pages = [...(ctx.relevant_pages ?? [])];
  const lowerName = pageName.toLowerCase();

  // Remove existing entry (case-insensitive) so we can re-add at end
  const existingIdx = pages.findIndex((p) => p.toLowerCase() === lowerName);
  if (existingIdx !== -1) {
    pages.splice(existingIdx, 1);
  }

  // Evict oldest (first) if at cap
  if (pages.length >= RELEVANT_PAGES_CAP) {
    pages.shift();
  }

  pages.push(pageName);
  return { ...ctx, relevant_pages: pages };
}

/**
 * Remove a page from the session's relevant_pages list (case-insensitive).
 * Returns the context unchanged if the page is not found.
 * Returns a new SessionContext (does not mutate the original).
 */
export function removeRelevantPage(
  ctx: SessionContext,
  pageName: string
): SessionContext {
  if (!ctx.relevant_pages) return ctx;
  const lowerName = pageName.toLowerCase();
  const filtered = ctx.relevant_pages.filter(
    (p) => p.toLowerCase() !== lowerName
  );
  return { ...ctx, relevant_pages: filtered };
}

// ---------------------------------------------------------------------------
// Session System Prompt Builder
// ---------------------------------------------------------------------------

const MAX_PAGE_CONTENT_LENGTH = 4000;
const MAX_TOTAL_PAGE_CONTENT = 12000;

/**
 * Build an enriched system prompt for a session.
 * Starts with the base agent system prompt, then appends session context
 * sections: session info, current focus, working memory, and relevant pages.
 * Page content is truncated to stay within token budgets.
 */
export function buildSessionSystemPrompt(
  session: Session,
  pageContents?: Map<string, string>
): string {
  const parts: string[] = [buildSystemPrompt()];
  const ctx = session.context;
  const hasContext =
    ctx.focus ||
    (ctx.working_memory && ctx.working_memory.length > 0) ||
    (ctx.relevant_pages && ctx.relevant_pages.length > 0);

  if (!hasContext && !session.name) return parts[0];

  parts.push("\n\n---\n\n## Session Context");

  if (session.name) {
    parts.push(`\n**Session:** ${session.name}`);
  }

  // Current Focus
  if (ctx.focus) {
    parts.push(`\n### Current Focus\n${ctx.focus}`);
  }

  // Working Memory
  if (ctx.working_memory && ctx.working_memory.length > 0) {
    const entries = ctx.working_memory
      .map((e) => `- **${e.key}**: ${e.value}`)
      .join("\n");
    parts.push(`\n### Working Memory\n${entries}`);
  }

  // Relevant Pages
  if (ctx.relevant_pages && ctx.relevant_pages.length > 0) {
    let pagesSection = "\n### Relevant Pages";
    let totalContent = 0;

    for (const pageName of ctx.relevant_pages) {
      const content = pageContents?.get(pageName);
      if (content && totalContent < MAX_TOTAL_PAGE_CONTENT) {
        const truncated =
          content.length > MAX_PAGE_CONTENT_LENGTH
            ? content.slice(0, MAX_PAGE_CONTENT_LENGTH) + "\n... (truncated)"
            : content;
        const remaining = MAX_TOTAL_PAGE_CONTENT - totalContent;
        const finalContent =
          truncated.length > remaining
            ? truncated.slice(0, remaining) + "\n... (truncated)"
            : truncated;
        pagesSection += `\n\n#### ${pageName}\n${finalContent}`;
        totalContent += finalContent.length;
      } else {
        pagesSection += `\n- ${pageName}`;
      }
    }

    parts.push(pagesSection);
  }

  return parts.join("");
}

// ---------------------------------------------------------------------------
// Page Content Resolution
// ---------------------------------------------------------------------------

const PAGE_RESOLVE_TIMEOUT = 2000;

/**
 * Resolve relevant page names to their content via the Agent Bridge.
 * Fetches pages in parallel using Promise.allSettled.
 * Failed pages are silently skipped. Returns empty map if bridge is disconnected.
 */
export async function resolveRelevantPages(
  bridge: AgentBridge,
  pageNames: string[]
): Promise<Map<string, string>> {
  const result = new Map<string, string>();
  if (pageNames.length === 0 || !bridge.isPluginConnected()) {
    return result;
  }

  const fetchPage = (name: string): Promise<{ name: string; content: string }> => {
    return new Promise(async (resolve, reject) => {
      const timer = setTimeout(
        () => reject(new Error(`Timeout resolving page: ${name}`)),
        PAGE_RESOLVE_TIMEOUT
      );
      try {
        const data = await bridge.sendRequest("page_read", { name });
        clearTimeout(timer);
        const content = typeof data === "string" ? data : JSON.stringify(data);
        resolve({ name, content });
      } catch (err) {
        clearTimeout(timer);
        reject(err);
      }
    });
  };

  const settled = await Promise.allSettled(pageNames.map(fetchPage));
  for (const outcome of settled) {
    if (outcome.status === "fulfilled") {
      result.set(outcome.value.name, outcome.value.content);
    }
  }

  return result;
}

// ---------------------------------------------------------------------------
// Message Summarization
// ---------------------------------------------------------------------------

const DEFAULT_SUMMARIZATION_MODEL = "anthropic/claude-haiku-4-5-20251001";
const SUMMARIZATION_PROMPT =
  "Summarize the following conversation history in 2-3 paragraphs, preserving key decisions, actions taken, and important context.";

export type LlmCallFn = (
  messages: Array<{ role: string; content: string }>,
  model: string
) => Promise<string>;

export interface SummarizeResult {
  summary: string;
  originalMessageCount: number;
}

/**
 * Summarize a batch of old session messages using a cheaper LLM model.
 * Formats non-system messages as a readable transcript and asks the LLM
 * to produce a 2-3 paragraph summary of key decisions and context.
 * Returns the summary string and the original message count.
 */
export async function summarizeMessages(
  messages: SessionMessage[],
  llmCall: LlmCallFn,
  model: string = DEFAULT_SUMMARIZATION_MODEL
): Promise<SummarizeResult> {
  const originalMessageCount = messages.length;

  // Format non-system messages as a readable transcript
  const transcript = messages
    .filter((m) => m.role !== "system")
    .map((m) => `${m.role.toUpperCase()}: ${m.content}`)
    .join("\n");

  const llmMessages: Array<{ role: string; content: string }> = [
    { role: "system", content: SUMMARIZATION_PROMPT },
    {
      role: "user",
      content: transcript || "(no conversation to summarize)",
    },
  ];

  const summary = await llmCall(llmMessages, model);

  return { summary, originalMessageCount };
}
