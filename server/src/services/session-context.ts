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
