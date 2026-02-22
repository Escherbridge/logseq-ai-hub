import type { JobCreateRequest } from "../types/agent";

const VALID_JOB_TYPES = ["autonomous", "manual", "scheduled", "event-driven"] as const;

export function validateJobCreate(body: unknown): { valid: true; data: JobCreateRequest } | { valid: false; errors: string[] } {
  const errors: string[] = [];

  if (!body || typeof body !== "object") {
    return { valid: false, errors: ["Request body must be a JSON object"] };
  }

  const b = body as Record<string, unknown>;

  if (!b.name || typeof b.name !== "string") {
    errors.push("Missing or invalid required field: name");
  }

  if (!b.type || typeof b.type !== "string") {
    errors.push("Missing or invalid required field: type");
  } else if (!VALID_JOB_TYPES.includes(b.type as any)) {
    errors.push(`Invalid type: ${b.type}. Must be one of: ${VALID_JOB_TYPES.join(", ")}`);
  }

  if (b.priority !== undefined) {
    if (typeof b.priority !== "number" || b.priority < 1 || b.priority > 5) {
      errors.push("priority must be a number between 1 and 5");
    }
  }

  if (b.type === "scheduled" && (!b.schedule || typeof b.schedule !== "string")) {
    errors.push("schedule is required when type is 'scheduled'");
  }

  if (errors.length > 0) return { valid: false, errors };

  return {
    valid: true,
    data: {
      name: b.name as string,
      type: b.type as JobCreateRequest["type"],
      priority: (b.priority as number) ?? 3,
      schedule: b.schedule as string | undefined,
      skill: b.skill as string | undefined,
      input: b.input as Record<string, unknown> | undefined,
      dependsOn: b.dependsOn as string[] | undefined,
    },
  };
}
