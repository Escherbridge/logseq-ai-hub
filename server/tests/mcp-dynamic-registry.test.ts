import { describe, test, expect, mock } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import {
  DynamicRegistry,
  jsonSchemaTypeToZod,
  jsonSchemaToZodParams,
  interpolateTemplate,
  type RegistryEntry,
} from "../src/services/mcp/dynamic-registry";
import { createTestDb, makeTestConfig } from "./helpers";
import { z } from "zod";
import type { Config } from "../src/config";

const testConfig: Config = makeTestConfig();

function createServer(): McpServer {
  return new McpServer(
    { name: "test-server", version: "0.0.1" },
    { capabilities: { tools: {}, resources: {}, prompts: {}, logging: {} } },
  );
}

function getRegisteredTools(server: McpServer): Record<string, any> {
  return (server as any)._registeredTools ?? {};
}

function getRegisteredPrompts(server: McpServer): Record<string, any> {
  return (server as any)._registeredPrompts ?? {};
}

function getRegisteredResources(server: McpServer): Record<string, any> {
  return (server as any)._registeredResources ?? {};
}

// ──────────────────────────────────────────────────────────────────────────────
// jsonSchemaTypeToZod
// ──────────────────────────────────────────────────────────────────────────────

describe("jsonSchemaTypeToZod", () => {
  test("converts string type", () => {
    const result = jsonSchemaTypeToZod({ type: "string" });
    expect(result.safeParse("hello").success).toBe(true);
    expect(result.safeParse(123).success).toBe(false);
  });

  test("converts number type", () => {
    const result = jsonSchemaTypeToZod({ type: "number" });
    expect(result.safeParse(42).success).toBe(true);
    expect(result.safeParse("x").success).toBe(false);
  });

  test("converts integer type to number", () => {
    const result = jsonSchemaTypeToZod({ type: "integer" });
    expect(result.safeParse(42).success).toBe(true);
  });

  test("converts boolean type", () => {
    const result = jsonSchemaTypeToZod({ type: "boolean" });
    expect(result.safeParse(true).success).toBe(true);
    expect(result.safeParse("yes").success).toBe(false);
  });

  test("converts array type", () => {
    const result = jsonSchemaTypeToZod({ type: "array" });
    expect(result.safeParse([1, 2, 3]).success).toBe(true);
  });

  test("converts object type", () => {
    const result = jsonSchemaTypeToZod({ type: "object" });
    // z.record(z.unknown()) is returned; verify it's a valid Zod schema
    expect(result).toBeDefined();
    expect(typeof result.safeParse).toBe("function");
  });

  test("defaults to string for unknown type", () => {
    const result = jsonSchemaTypeToZod({ type: "foobar" });
    expect(result.safeParse("hello").success).toBe(true);
  });

  test("adds description when provided", () => {
    const result = jsonSchemaTypeToZod({
      type: "string",
      description: "A name",
    });
    expect(result.description).toBe("A name");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// jsonSchemaToZodParams
// ──────────────────────────────────────────────────────────────────────────────

describe("jsonSchemaToZodParams", () => {
  test("converts properties with required fields", () => {
    const params = jsonSchemaToZodParams({
      type: "object",
      properties: {
        name: { type: "string" },
        age: { type: "number" },
      },
      required: ["name"],
    });

    expect("name" in params).toBe(true);
    expect("age" in params).toBe(true);
    // name is required (not optional)
    expect(params.name.safeParse("Alice").success).toBe(true);
    expect(params.name.safeParse(undefined).success).toBe(false);
    // age is optional
    expect(params.age.safeParse(undefined).success).toBe(true);
  });

  test("handles empty schema", () => {
    const params = jsonSchemaToZodParams({});
    expect(Object.keys(params).length).toBe(0);
  });

  test("handles schema without required array", () => {
    const params = jsonSchemaToZodParams({
      type: "object",
      properties: { x: { type: "string" } },
    });
    // All optional when no required array
    expect(params.x.safeParse(undefined).success).toBe(true);
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// interpolateTemplate
// ──────────────────────────────────────────────────────────────────────────────

describe("interpolateTemplate", () => {
  test("replaces {{var}} placeholders", () => {
    expect(interpolateTemplate("Hello {{name}}", { name: "World" })).toBe(
      "Hello World",
    );
  });

  test("replaces multiple variables", () => {
    expect(
      interpolateTemplate("{{a}} and {{b}}", { a: "X", b: "Y" }),
    ).toBe("X and Y");
  });

  test("preserves unmatched placeholders", () => {
    expect(interpolateTemplate("{{a}} {{b}}", { a: "X" })).toBe("X {{b}}");
  });

  test("handles null template", () => {
    expect(interpolateTemplate(null, { a: "1" })).toBe("");
  });

  test("handles undefined template", () => {
    expect(interpolateTemplate(undefined, { a: "1" })).toBe("");
  });

  test("handles empty args", () => {
    expect(interpolateTemplate("no vars here", {})).toBe("no vars here");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// DynamicRegistry.syncFromBridge
// ──────────────────────────────────────────────────────────────────────────────

describe("DynamicRegistry.syncFromBridge", () => {
  function createMockBridge(entries: Record<string, RegistryEntry[]> = {}) {
    return {
      isPluginConnected: () => true,
      sendRequest: mock(async (op: string, params: any) => {
        if (op === "registry_list") {
          const type = params.type as string;
          return {
            entries: entries[type] ?? [],
            count: (entries[type] ?? []).length,
            version: 1,
          };
        }
        return {};
      }),
    };
  }

  test("registers tools from bridge data", async () => {
    const server = createServer();
    const bridge = createMockBridge({
      tool: [
        {
          id: "Tools/my-tool",
          type: "tool",
          name: "my-tool",
          description: "A test tool",
          handler: "http",
          "input-schema": {
            type: "object",
            properties: { query: { type: "string" } },
            required: ["query"],
          },
          properties: { "tool-http-url": "https://example.com" },
        },
      ],
    });

    const registry = new DynamicRegistry(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    const result = await registry.syncFromBridge();
    expect(result.tools).toBe(1);

    const tools = getRegisteredTools(server);
    expect("kb_my-tool" in tools).toBe(true);
  });

  test("registers skills as tools", async () => {
    const server = createServer();
    const bridge = createMockBridge({
      skill: [
        {
          id: "Skills/summarize",
          type: "skill",
          name: "summarize",
          description: "Summarize text",
        },
      ],
    });

    const registry = new DynamicRegistry(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    const result = await registry.syncFromBridge();
    expect(result.tools).toBe(1);

    const tools = getRegisteredTools(server);
    expect("skill_summarize" in tools).toBe(true);
  });

  test("registers prompts with per-argument schemas", async () => {
    const server = createServer();
    const bridge = createMockBridge({
      prompt: [
        {
          id: "Prompts/review",
          type: "prompt",
          name: "code-review",
          description: "Review code",
          arguments: ["code", "language"],
        },
      ],
    });

    const registry = new DynamicRegistry(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    const result = await registry.syncFromBridge();
    expect(result.prompts).toBe(1);

    const prompts = getRegisteredPrompts(server);
    expect("kb_code-review" in prompts).toBe(true);
  });

  test("registers procedures as resources", async () => {
    const server = createServer();
    const bridge = createMockBridge({
      procedure: [
        {
          id: "Procedures/deploy",
          type: "procedure",
          name: "deploy",
          description: "Deploy to prod",
        },
      ],
    });

    const registry = new DynamicRegistry(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    const result = await registry.syncFromBridge();
    expect(result.resources).toBe(1);

    const resources = getRegisteredResources(server);
    expect("logseq://procedures/deploy" in resources).toBe(true);
  });

  test("returns zero counts when bridge is disconnected", async () => {
    const server = createServer();
    const bridge = {
      isPluginConnected: () => false,
      sendRequest: mock(async () => ({})),
    };

    const registry = new DynamicRegistry(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    const result = await registry.syncFromBridge();
    expect(result.tools).toBe(0);
    expect(result.prompts).toBe(0);
    expect(result.resources).toBe(0);
    expect(bridge.sendRequest).not.toHaveBeenCalled();
  });

  test("handles bridge errors gracefully", async () => {
    const server = createServer();
    const bridge = {
      isPluginConnected: () => true,
      sendRequest: mock(async () => {
        throw new Error("Connection lost");
      }),
    };

    const registry = new DynamicRegistry(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    const result = await registry.syncFromBridge();
    expect(result.tools).toBe(0);
    expect(result.prompts).toBe(0);
    expect(result.resources).toBe(0);
  });

  test("does not re-register already registered items", async () => {
    const server = createServer();
    const toolEntry: RegistryEntry = {
      id: "Tools/dup",
      type: "tool",
      name: "dup",
      description: "Duplicate test",
      handler: "http",
      properties: { "tool-http-url": "https://example.com" },
    };
    const bridge = createMockBridge({ tool: [toolEntry] });

    const registry = new DynamicRegistry(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    const result1 = await registry.syncFromBridge();
    expect(result1.tools).toBe(1);

    const result2 = await registry.syncFromBridge();
    expect(result2.tools).toBe(0); // Already registered, not counted again
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// MCP notifications
// ──────────────────────────────────────────────────────────────────────────────

describe("MCP notifications after sync", () => {
  test("sends tool list changed notification when new tools registered", async () => {
    const server = createServer();
    const sendToolListChanged = mock(() => {});
    (server as any).sendToolListChanged = sendToolListChanged;

    const bridge = {
      isPluginConnected: () => true,
      sendRequest: mock(async (op: string, params: any) => {
        if (op === "registry_list" && params.type === "tool") {
          return {
            entries: [
              {
                id: "Tools/t1",
                type: "tool",
                name: "t1",
                description: "Test",
                handler: "http",
                properties: { "tool-http-url": "https://example.com" },
              },
            ],
            count: 1,
            version: 1,
          };
        }
        return { entries: [], count: 0, version: 1 };
      }),
    };

    const registry = new DynamicRegistry(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    await registry.syncFromBridge();
    expect(sendToolListChanged).toHaveBeenCalled();
  });

  test("does not send notification if no new registrations", async () => {
    const server = createServer();
    const sendToolListChanged = mock(() => {});
    (server as any).sendToolListChanged = sendToolListChanged;

    const bridge = {
      isPluginConnected: () => true,
      sendRequest: mock(async () => ({
        entries: [],
        count: 0,
        version: 1,
      })),
    };

    const registry = new DynamicRegistry(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    await registry.syncFromBridge();
    expect(sendToolListChanged).not.toHaveBeenCalled();
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// DynamicRegistry multi-target attach/detach (per-session MCP servers)
// ──────────────────────────────────────────────────────────────────────────────

describe("DynamicRegistry multi-target attach/detach", () => {
  /**
   * Invokes a registered tool's real callback directly (the same
   * `tool.handler` the SDK's CallToolRequest dispatcher would invoke),
   * proving the tool is actually callable on `server` rather than merely
   * present as a bookkeeping key.
   */
  function callTool(
    server: McpServer,
    name: string,
    args: Record<string, unknown> = {},
  ): Promise<any> {
    const tool = getRegisteredTools(server)[name];
    if (!tool) throw new Error(`Tool ${name} not registered on server`);
    return tool.handler(args, {});
  }

  function createExecuteSkillBridge(entries: Record<string, RegistryEntry[]>) {
    return {
      isPluginConnected: () => true,
      sendRequest: mock(async (op: string, params: any) => {
        if (op === "registry_list") {
          const type = params.type as string;
          return {
            entries: entries[type] ?? [],
            count: (entries[type] ?? []).length,
            version: 1,
          };
        }
        if (op === "execute_skill") {
          // Echo back what the tool handler actually sent, so the test
          // can assert real argument flow end-to-end rather than just a
          // fixed stub response.
          return { skillId: params.skillId, inputsSeen: params.inputs };
        }
        return {};
      }),
    };
  }

  test("a tool registered before a server attaches is replayed onto it and is callable on both", async () => {
    const serverAttachedBefore = createServer();
    const toolEntry: RegistryEntry = {
      id: "Skills/replay-me",
      type: "tool",
      name: "replay-me",
      description: "Tool used to prove replay works",
      handler: "skill",
      "skill-id": "Skills/replay-me",
      "input-schema": {
        type: "object",
        properties: { note: { type: "string" } },
      },
    };
    const bridge = createExecuteSkillBridge({ tool: [toolEntry] });

    const registry = new DynamicRegistry(serverAttachedBefore, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    await registry.syncFromBridge();

    // Attached BEFORE the sync ran — must be callable, not just listed.
    const resultBefore = await callTool(serverAttachedBefore, "kb_replay-me", {
      note: "hi",
    });
    const payloadBefore = JSON.parse(resultBefore.content[0].text);
    expect(payloadBefore.skillId).toBe("Skills/replay-me");
    expect(payloadBefore.inputsSeen).toEqual({ note: "hi" });

    // Attached AFTER the sync already ran — replay must register it too,
    // and with a working, independently-callable closure (different args
    // produce a different observable result).
    const serverAttachedAfter = createServer();
    registry.attach(serverAttachedAfter);

    const resultAfter = await callTool(serverAttachedAfter, "kb_replay-me", {
      note: "later",
    });
    const payloadAfter = JSON.parse(resultAfter.content[0].text);
    expect(payloadAfter.skillId).toBe("Skills/replay-me");
    expect(payloadAfter.inputsSeen).toEqual({ note: "later" });
  });

  test("detach stops a server from receiving tools registered afterward", async () => {
    const serverStaysAttached = createServer();
    const serverDetached = createServer();

    // A bridge whose entry list can change between successive
    // syncFromBridge() calls, mirroring how the real plugin bridge
    // reports newly-added registry entries over time.
    let entries: Record<string, RegistryEntry[]> = {};
    const bridge = {
      isPluginConnected: () => true,
      sendRequest: mock(async (op: string, params: any) => {
        if (op === "registry_list") {
          const type = params.type as string;
          return {
            entries: entries[type] ?? [],
            count: (entries[type] ?? []).length,
            version: 1,
          };
        }
        return {};
      }),
    };

    const registry = new DynamicRegistry(serverStaysAttached, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));

    // Attach, then detach, before any tool exists at all — simulating a
    // session that connected and disconnected before the next poll.
    registry.attach(serverDetached);
    registry.detach(serverDetached);

    entries = {
      tool: [
        {
          id: "Skills/after-detach",
          type: "tool",
          name: "after-detach",
          description: "Registered after detach",
          handler: "skill",
          "skill-id": "Skills/after-detach",
        },
      ],
    };
    await registry.syncFromBridge();

    // Mutation check: if detach() were a no-op, serverDetached would
    // still be in the internal target list and would pick this up too.
    expect(
      "kb_after-detach" in getRegisteredTools(serverStaysAttached),
    ).toBe(true);
    expect("kb_after-detach" in getRegisteredTools(serverDetached)).toBe(
      false,
    );
  });

  // Regression: registerSkillAsTool declared `inputs` as the zod-3 one-argument
  // z.record(z.string()). Under zod 4 that schema constructs fine and validates
  // an OMITTED `inputs`, but throws on any supplied object — so every skill_*
  // call that actually passed inputs died inside the SDK's argument validation.
  // Exercising inputSchema is what the protocol does for tools/call; invoking
  // tool.handler directly skips validation and would not catch this.
  test("a skill tool validates supplied inputs and forwards them to the bridge", async () => {
    const server = createServer();
    const bridge = createExecuteSkillBridge({
      skill: [
        {
          id: "Skills/summarize",
          type: "skill",
          name: "summarize",
          description: "Summarize text",
        },
      ],
    });

    const registry = new DynamicRegistry(server, () => ({
      db: createTestDb(),
      config: testConfig,
      bridge: bridge as any,
    }));
    await registry.syncFromBridge();

    const registered = getRegisteredTools(server)["skill_summarize"];
    expect(registered).toBeTruthy();

    // The schema the SDK validates tools/call arguments against.
    const parsed = (registered.inputSchema as any).parse({
      inputs: { topic: "meeting notes" },
    });
    expect(parsed.inputs).toEqual({ topic: "meeting notes" });

    // And the validated inputs reach the plugin bridge unchanged.
    const result = await callTool(server, "skill_summarize", {
      inputs: { topic: "meeting notes" },
    });
    const payload = JSON.parse(result.content[0].text);
    expect(payload.skillId).toBe("Skills/summarize");
    expect(payload.inputsSeen).toEqual({ topic: "meeting notes" });
  });
});
