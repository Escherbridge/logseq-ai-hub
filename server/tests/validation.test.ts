import { describe, test, expect } from "bun:test";
import { validateJobCreate } from "../src/validation/jobs";
import { validateSkillCreate } from "../src/validation/skills";

describe("validateJobCreate", () => {
  test("valid minimal job", () => {
    const result = validateJobCreate({ name: "test-job", type: "autonomous" });
    expect(result.valid).toBe(true);
  });

  test("missing name", () => {
    const result = validateJobCreate({ type: "autonomous" });
    expect(result.valid).toBe(false);
    if (!result.valid) expect(result.errors[0]).toContain("name");
  });

  test("missing type", () => {
    const result = validateJobCreate({ name: "test" });
    expect(result.valid).toBe(false);
    if (!result.valid) expect(result.errors[0]).toContain("type");
  });

  test("invalid type", () => {
    const result = validateJobCreate({ name: "test", type: "invalid" });
    expect(result.valid).toBe(false);
    if (!result.valid) expect(result.errors[0]).toContain("Invalid type");
  });

  test("invalid priority", () => {
    const result = validateJobCreate({ name: "test", type: "autonomous", priority: 10 });
    expect(result.valid).toBe(false);
  });

  test("scheduled without schedule", () => {
    const result = validateJobCreate({ name: "test", type: "scheduled" });
    expect(result.valid).toBe(false);
    if (!result.valid) expect(result.errors.some((e: string) => e.includes("schedule"))).toBe(true);
  });

  test("scheduled with schedule", () => {
    const result = validateJobCreate({ name: "test", type: "scheduled", schedule: "0 9 * * *" });
    expect(result.valid).toBe(true);
  });

  test("full valid payload", () => {
    const result = validateJobCreate({
      name: "daily-summary",
      type: "autonomous",
      priority: 2,
      skill: "Skills/summarize",
      input: { query: "today" },
      dependsOn: ["Jobs/setup"],
    });
    expect(result.valid).toBe(true);
    if (result.valid) {
      expect(result.data.name).toBe("daily-summary");
      expect(result.data.priority).toBe(2);
    }
  });
});

describe("validateSkillCreate", () => {
  test("valid skill", () => {
    const result = validateSkillCreate({
      name: "summarize",
      type: "llm-chain",
      description: "Summarizes text",
      inputs: ["query"],
      outputs: ["summary"],
      steps: [{ order: 1, action: "llm-call" }],
    });
    expect(result.valid).toBe(true);
  });

  test("missing required fields", () => {
    const result = validateSkillCreate({ name: "test" });
    expect(result.valid).toBe(false);
    if (!result.valid) expect(result.errors.length).toBeGreaterThan(0);
  });

  test("invalid step action", () => {
    const result = validateSkillCreate({
      name: "test",
      type: "llm-chain",
      description: "Test",
      inputs: [],
      outputs: [],
      steps: [{ order: 1, action: "invalid-action" }],
    });
    expect(result.valid).toBe(false);
  });

  test("empty steps", () => {
    const result = validateSkillCreate({
      name: "test",
      type: "llm-chain",
      description: "Test",
      inputs: [],
      outputs: [],
      steps: [],
    });
    expect(result.valid).toBe(false);
  });
});
