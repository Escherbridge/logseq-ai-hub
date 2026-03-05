import type {
  SessionContext,
  WorkingMemoryEntry,
} from "../types/session";

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
