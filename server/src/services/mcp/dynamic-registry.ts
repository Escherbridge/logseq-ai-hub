import { z, type ZodTypeAny } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";

/**
 * Shape of a registry entry as returned by the plugin bridge.
 */
export interface RegistryEntry {
  id: string;
  type: string;
  name: string;
  description: string;
  properties?: Record<string, unknown>;
  source?: string;
  handler?: string;
  // Tool-specific
  "input-schema"?: Record<string, unknown>;
  "skill-id"?: string;
  // Prompt-specific
  arguments?: string[];
  "system-section"?: string;
  "user-section"?: string;
  // Procedure-specific
  "requires-approval"?: boolean;
  "approval-contact"?: string;
  body?: string;
}

export interface RegistrySnapshot {
  entries: RegistryEntry[];
  count: number;
  version: number;
}

/**
 * Converts a JSON Schema type string to a Zod schema.
 */
export function jsonSchemaTypeToZod(
  typeDef: Record<string, unknown>,
): ZodTypeAny {
  const type = typeDef.type as string;
  const description = typeDef.description as string | undefined;

  let schema: ZodTypeAny;
  switch (type) {
    case "string":
      schema = z.string();
      break;
    case "number":
    case "integer":
      schema = z.number();
      break;
    case "boolean":
      schema = z.boolean();
      break;
    case "array":
      schema = z.array(z.unknown());
      break;
    case "object":
      schema = z.record(z.string(), z.unknown());
      break;
    default:
      schema = z.string();
  }

  if (description) {
    schema = schema.describe(description);
  }

  return schema;
}

/**
 * Converts a JSON Schema properties object into a Zod params shape.
 */
export function jsonSchemaToZodParams(
  schema: Record<string, unknown>,
): Record<string, ZodTypeAny> {
  const properties = (schema.properties ?? {}) as Record<
    string,
    Record<string, unknown>
  >;
  const required = (schema.required ?? []) as string[];
  const result: Record<string, ZodTypeAny> = {};

  for (const [key, typeDef] of Object.entries(properties)) {
    let zodType = jsonSchemaTypeToZod(typeDef);
    if (!required.includes(key)) {
      zodType = zodType.optional();
    }
    result[key] = zodType;
  }

  return result;
}

/**
 * Interpolates {{variable}} placeholders in a template string.
 */
export function interpolateTemplate(
  template: string | null | undefined,
  args: Record<string, string>,
): string {
  if (!template) return "";
  return template.replace(
    /\{\{(\w+)\}\}/g,
    (_, key) => args[key] ?? `{{${key}}}`,
  );
}

/**
 * Manages dynamic MCP tool, prompt, and resource registration
 * based on the plugin-side registry.
 */
export class DynamicRegistry {
  private registeredTools = new Set<string>();
  private registeredPrompts = new Set<string>();
  private registeredResources = new Set<string>();

  constructor(
    private server: McpServer,
    private getContext: () => McpToolContext,
  ) {}

  /**
   * Fetches registry data from the plugin via bridge and syncs MCP registrations.
   */
  async syncFromBridge(): Promise<{
    tools: number;
    prompts: number;
    resources: number;
  }> {
    const ctx = this.getContext();
    if (!ctx.bridge?.isPluginConnected()) {
      return { tools: 0, prompts: 0, resources: 0 };
    }

    try {
      // Capture sizes before registration for notification tracking
      const prevToolCount = this.registeredTools.size;
      const prevPromptCount = this.registeredPrompts.size;
      const prevResourceCount = this.registeredResources.size;

      // Fetch all entry types (bridge now returns full entries)
      const [toolsResult, skillsResult, promptsResult, proceduresResult] =
        await Promise.all([
          ctx.bridge.sendRequest(
            "registry_list",
            { type: "tool" },
            ctx.traceId,
          ),
          ctx.bridge.sendRequest(
            "registry_list",
            { type: "skill" },
            ctx.traceId,
          ),
          ctx.bridge.sendRequest(
            "registry_list",
            { type: "prompt" },
            ctx.traceId,
          ),
          ctx.bridge.sendRequest(
            "registry_list",
            { type: "procedure" },
            ctx.traceId,
          ),
        ]);

      const tools = ((toolsResult as any)?.entries ?? []) as RegistryEntry[];
      const skills = ((skillsResult as any)?.entries ?? []) as RegistryEntry[];
      const prompts = ((promptsResult as any)?.entries ??
        []) as RegistryEntry[];
      const procedures = ((proceduresResult as any)?.entries ??
        []) as RegistryEntry[];

      let toolCount = 0;
      let promptCount = 0;
      let resourceCount = 0;

      // Register tools from custom tool pages
      for (const tool of tools) {
        if (this.registerDynamicTool(tool)) toolCount++;
      }

      // Register skills as tools
      for (const skill of skills) {
        if (this.registerSkillAsTool(skill)) toolCount++;
      }

      // Register prompts
      for (const prompt of prompts) {
        if (this.registerDynamicPrompt(prompt)) promptCount++;
      }

      // Register procedures as resources
      for (const procedure of procedures) {
        if (this.registerDynamicResource(procedure)) resourceCount++;
      }

      // Notify MCP clients if registrations changed (Task 5)
      if (this.registeredTools.size > prevToolCount) {
        try {
          this.server.sendToolListChanged();
        } catch {
          /* no active sessions */
        }
      }
      if (this.registeredPrompts.size > prevPromptCount) {
        try {
          this.server.sendPromptListChanged();
        } catch {
          /* no active sessions */
        }
      }
      if (this.registeredResources.size > prevResourceCount) {
        try {
          this.server.sendResourceListChanged();
        } catch {
          /* no active sessions */
        }
      }

      return { tools: toolCount, prompts: promptCount, resources: resourceCount };
    } catch (err: any) {
      console.error("Dynamic registry sync failed:", err.message);
      return { tools: 0, prompts: 0, resources: 0 };
    }
  }

  /**
   * Executes a dynamic tool based on its handler type.
   */
  private async executeDynamicTool(
    entry: RegistryEntry,
    args: Record<string, unknown>,
    ctx: McpToolContext,
  ): Promise<unknown> {
    const handler =
      entry.handler || (entry.properties?.["tool-handler"] as string);

    switch (handler) {
      case "skill":
      case "mcp-tool": {
        const skillId =
          entry["skill-id"] ||
          (entry.properties?.["tool-skill"] as string) ||
          entry.id;
        return ctx.bridge!.sendRequest(
          "execute_skill",
          { skillId, inputs: args },
          ctx.traceId,
        );
      }

      case "http": {
        const url = entry.properties?.["tool-http-url"] as string;
        const method =
          (entry.properties?.["tool-http-method"] as string) || "POST";
        if (!url) throw new Error("HTTP tool missing tool-http-url property");
        const response = await fetch(url, {
          method,
          headers: { "Content-Type": "application/json" },
          body: method !== "GET" ? JSON.stringify(args) : undefined,
        });
        return response.json();
      }

      case "graph-query": {
        const queryTemplate = entry.properties?.["tool-query"] as string;
        if (!queryTemplate)
          throw new Error("graph-query tool missing tool-query property");
        const query = interpolateTemplate(
          queryTemplate,
          args as Record<string, string>,
        );
        return ctx.bridge!.sendRequest("graph_query", { query }, ctx.traceId);
      }

      default:
        throw new Error(`Unknown tool handler type: ${handler}`);
    }
  }

  private registerDynamicTool(entry: RegistryEntry): boolean {
    const toolName = `kb_${entry.name}`;
    if (this.registeredTools.has(toolName)) return false;

    try {
      const description = entry.description || "Knowledge base tool";

      // Build real Zod params from input-schema (Task 1)
      const zodParams = entry["input-schema"]
        ? jsonSchemaToZodParams(entry["input-schema"] as Record<string, unknown>)
        : {};

      this.server.tool(toolName, description, zodParams, async (args) => {
        const ctx = this.getContext();
        if (!ctx.bridge?.isPluginConnected()) {
          return {
            content: [
              {
                type: "text" as const,
                text: "Error: Logseq plugin not connected",
              },
            ],
            isError: true as const,
          };
        }

        try {
          const result = await this.executeDynamicTool(entry, args, ctx);
          return {
            content: [
              {
                type: "text" as const,
                text: JSON.stringify(result, null, 2),
              },
            ],
          };
        } catch (err: any) {
          return {
            content: [
              { type: "text" as const, text: `Error: ${err.message}` },
            ],
            isError: true as const,
          };
        }
      });

      this.registeredTools.add(toolName);
      return true;
    } catch (err: any) {
      console.warn(
        `Failed to register dynamic tool ${toolName}:`,
        err.message,
      );
      return false;
    }
  }

  private registerSkillAsTool(entry: RegistryEntry): boolean {
    const toolName = `skill_${entry.name}`;
    if (this.registeredTools.has(toolName)) return false;

    try {
      this.server.tool(
        toolName,
        entry.description || `Execute skill: ${entry.name}`,
        {
          inputs: z
            .record(z.string())
            .optional()
            .describe("Skill input parameters"),
        },
        async ({ inputs }) => {
          const ctx = this.getContext();
          if (!ctx.bridge?.isPluginConnected()) {
            return {
              content: [
                {
                  type: "text" as const,
                  text: "Error: Logseq plugin not connected",
                },
              ],
              isError: true as const,
            };
          }

          try {
            const result = await ctx.bridge.sendRequest(
              "execute_skill",
              { skillId: entry.id, inputs: inputs ?? {} },
              ctx.traceId,
            );
            return {
              content: [
                {
                  type: "text" as const,
                  text: JSON.stringify(result, null, 2),
                },
              ],
            };
          } catch (err: any) {
            return {
              content: [
                { type: "text" as const, text: `Error: ${err.message}` },
              ],
              isError: true as const,
            };
          }
        },
      );

      this.registeredTools.add(toolName);
      return true;
    } catch (err: any) {
      console.warn(
        `Failed to register skill tool ${toolName}:`,
        err.message,
      );
      return false;
    }
  }

  private registerDynamicPrompt(entry: RegistryEntry): boolean {
    const promptName = `kb_${entry.name}`;
    if (this.registeredPrompts.has(promptName)) return false;

    try {
      // Build per-argument Zod params (Task 2)
      const argSchema: Record<string, ZodTypeAny> = {};
      const declaredArgs = entry.arguments ?? [];
      if (declaredArgs.length > 0) {
        for (const argName of declaredArgs) {
          argSchema[argName] = z
            .string()
            .optional()
            .describe(`Prompt argument: ${argName}`);
        }
      } else {
        // Fallback: generic args parameter for prompts without declared arguments
        argSchema.args = z
          .string()
          .optional()
          .describe("Arguments for the prompt template");
      }

      this.server.prompt(
        promptName,
        entry.description || `Knowledge base prompt: ${entry.name}`,
        argSchema,
        async (params) => {
          const ctx = this.getContext();
          if (!ctx.bridge?.isPluginConnected()) {
            return {
              messages: [
                {
                  role: "user" as const,
                  content: {
                    type: "text" as const,
                    text: "Error: Logseq plugin not connected",
                  },
                },
              ],
            };
          }

          try {
            const fullEntry = (await ctx.bridge.sendRequest(
              "registry_get",
              { name: entry.id, type: "prompt" },
              ctx.traceId,
            )) as Record<string, unknown>;

            const systemSection = fullEntry["system-section"] as
              | string
              | undefined;
            const userSection = fullEntry["user-section"] as
              | string
              | undefined;

            // Interpolate templates with provided arguments (Task 2)
            const argValues = params as Record<string, string>;
            const interpolatedSystem = interpolateTemplate(
              systemSection,
              argValues,
            );
            const interpolatedUser = interpolateTemplate(
              userSection,
              argValues,
            );

            const messages: Array<{
              role: "user" | "assistant";
              content: { type: "text"; text: string };
            }> = [];

            if (interpolatedSystem) {
              messages.push({
                role: "user" as const,
                content: {
                  type: "text" as const,
                  text: `[System Context]\n${interpolatedSystem}`,
                },
              });
            }

            if (interpolatedUser) {
              messages.push({
                role: "user" as const,
                content: {
                  type: "text" as const,
                  text: interpolatedUser,
                },
              });
            }

            if (messages.length === 0) {
              messages.push({
                role: "user" as const,
                content: {
                  type: "text" as const,
                  text: entry.description || "No prompt content found",
                },
              });
            }

            return { messages };
          } catch (err: any) {
            return {
              messages: [
                {
                  role: "user" as const,
                  content: {
                    type: "text" as const,
                    text: `Error loading prompt: ${err.message}`,
                  },
                },
              ],
            };
          }
        },
      );

      this.registeredPrompts.add(promptName);
      return true;
    } catch (err: any) {
      console.warn(
        `Failed to register dynamic prompt ${promptName}:`,
        err.message,
      );
      return false;
    }
  }

  private registerDynamicResource(entry: RegistryEntry): boolean {
    const resourceUri = `logseq://procedures/${entry.name}`;
    if (this.registeredResources.has(resourceUri)) return false;

    try {
      this.server.resource(
        `procedure-${entry.name}`,
        resourceUri,
        {
          description: entry.description || `Procedure: ${entry.name}`,
          mimeType: "application/json",
        },
        async (uri) => {
          const ctx = this.getContext();
          if (!ctx.bridge?.isPluginConnected()) {
            return {
              contents: [
                {
                  uri: uri.href,
                  text: "Error: Logseq plugin not connected",
                  mimeType: "text/plain",
                },
              ],
            };
          }

          try {
            const result = await ctx.bridge.sendRequest(
              "registry_get",
              { name: entry.id, type: "procedure" },
              ctx.traceId,
            );
            return {
              contents: [
                {
                  uri: uri.href,
                  text: JSON.stringify(result, null, 2),
                  mimeType: "application/json",
                },
              ],
            };
          } catch (err: any) {
            return {
              contents: [
                {
                  uri: uri.href,
                  text: `Error: ${err.message}`,
                  mimeType: "text/plain",
                },
              ],
            };
          }
        },
      );

      this.registeredResources.add(resourceUri);
      return true;
    } catch (err: any) {
      console.warn(
        `Failed to register procedure resource ${resourceUri}:`,
        err.message,
      );
      return false;
    }
  }
}
