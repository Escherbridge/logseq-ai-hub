import type { SkillCreateRequest } from "../types/agent";

const VALID_SKILL_TYPES = ["llm-chain", "tool-chain", "composite", "mcp-tool"] as const;
const VALID_STEP_ACTIONS = [
  "graph-query", "llm-call", "block-insert", "block-update", "page-create",
  "mcp-tool", "mcp-resource", "transform", "conditional", "sub-skill", "legacy-task"
] as const;

export function validateSkillCreate(body: unknown): { valid: true; data: SkillCreateRequest } | { valid: false; errors: string[] } {
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
  } else if (!VALID_SKILL_TYPES.includes(b.type as any)) {
    errors.push(`Invalid type: ${b.type}. Must be one of: ${VALID_SKILL_TYPES.join(", ")}`);
  }

  if (!b.description || typeof b.description !== "string") {
    errors.push("Missing or invalid required field: description");
  }

  if (!Array.isArray(b.inputs)) {
    errors.push("Missing or invalid required field: inputs (must be array)");
  }

  if (!Array.isArray(b.outputs)) {
    errors.push("Missing or invalid required field: outputs (must be array)");
  }

  if (!Array.isArray(b.steps) || b.steps.length === 0) {
    errors.push("Missing or invalid required field: steps (must be non-empty array)");
  } else {
    (b.steps as any[]).forEach((step, idx) => {
      if (typeof step.order !== "number") {
        errors.push(`Step ${idx}: missing required field 'order'`);
      }
      if (!step.action || typeof step.action !== "string") {
        errors.push(`Step ${idx}: missing required field 'action'`);
      } else if (!VALID_STEP_ACTIONS.includes(step.action as any)) {
        errors.push(`Step ${idx}: invalid action '${step.action}'. Must be one of: ${VALID_STEP_ACTIONS.join(", ")}`);
      }
    });
  }

  if (errors.length > 0) return { valid: false, errors };

  return {
    valid: true,
    data: {
      name: b.name as string,
      type: b.type as SkillCreateRequest["type"],
      description: b.description as string,
      inputs: b.inputs as string[],
      outputs: b.outputs as string[],
      tags: b.tags as string[] | undefined,
      steps: (b.steps as any[]).map(s => ({
        order: s.order,
        action: s.action,
        config: s.config,
        promptTemplate: s.promptTemplate,
        model: s.model,
        mcpServer: s.mcpServer,
        mcpTool: s.mcpTool,
      })),
    },
  };
}
