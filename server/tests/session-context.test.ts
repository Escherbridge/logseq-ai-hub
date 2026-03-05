import { describe, it, expect } from "bun:test";
import type { SessionContext, WorkingMemoryEntry } from "../src/types/session";
import {
  mergeSessionContext,
  addWorkingMemory,
  removeWorkingMemory,
} from "../src/services/session-context";

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
