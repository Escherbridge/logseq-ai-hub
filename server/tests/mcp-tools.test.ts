import { describe, test, expect, mock } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerGraphTools } from "../src/services/mcp/graph-tools";
import { registerJobTools } from "../src/services/mcp/job-tools";
import { registerMemoryTools } from "../src/services/mcp/memory-tools";
import { registerMessagingTools } from "../src/services/mcp/messaging-tools";
import { createTestDb, seedTestContact, makeTestConfig } from "./helpers";
import type { Config } from "../src/config";

const testConfig: Config = makeTestConfig();

function createServer(): McpServer {
  return new McpServer(
    { name: "test-server", version: "0.0.1" },
    { capabilities: { tools: {}, resources: {}, prompts: {}, logging: {} } },
  );
}

/** The MCP SDK stores registrations as plain objects keyed by name/uri. */
function getRegisteredTools(server: McpServer): Record<string, any> {
  return (server as any)._registeredTools as Record<string, any>;
}

// ──────────────────────────────────────────────────────────────────────────────
// Graph tools
// ──────────────────────────────────────────────────────────────────────────────

const GRAPH_TOOL_NAMES = [
  "graph_query",
  "graph_search",
  "page_list",
  "page_read",
  "block_get",
  "page_links",
  "page_create",
  "page_delete",
  "page_rename",
  "block_insert",
  "block_update",
  "block_delete",
  "block_move",
  "properties_set",
  "page_link",
  "journal_append",
];

function makeMockBridge(sendRequest: ReturnType<typeof mock>, connected = true) {
  return { isPluginConnected: () => connected, sendRequest, pendingCount: 0 } as any;
}

describe("registerGraphTools", () => {
  test("registers all 16 graph tools (block_append is no longer an MCP tool)", () => {
    const server = createServer();
    registerGraphTools(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);
    for (const name of GRAPH_TOOL_NAMES) {
      expect(name in tools).toBe(true);
    }
    expect(Object.keys(tools).length).toBe(GRAPH_TOOL_NAMES.length);
    expect("block_append" in tools).toBe(false);
  });

  test("graph_query calls bridge.sendRequest with 'graph_query' operation and returns compact JSON", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ results: [{ p: 1 }], total: 1, truncated: false }));
    registerGraphTools(server, () => ({ bridge: makeMockBridge(sendRequest), db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["graph_query"].handler({ query: "[:find ?p :where [?p :block/name]]" });

    expect(sendRequest.mock.calls[0][0]).toBe("graph_query");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ query: "[:find ?p :where [?p :block/name]]" });
    // Compact: no pretty-print whitespace.
    expect(result.content[0].text).toBe(JSON.stringify({ results: [{ p: 1 }], total: 1, truncated: false }));
  });

  test("page_read renders a contract-shaped bridge result as a markdown outline and forwards only {name}", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({
      page: { name: "My Notes", uuid: "page-uuid", journal: false },
      blocks: [{ uuid: "b1", content: "First block", children: [] }],
    }));
    registerGraphTools(server, () => ({ bridge: makeMockBridge(sendRequest), db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["page_read"].handler({ name: "My Notes", max_blocks: 50, uuids: true });

    expect(sendRequest.mock.calls[0][0]).toBe("page_read");
    // max_blocks/uuids are server-side render options -- never forwarded to the bridge.
    expect(sendRequest.mock.calls[0][1]).toEqual({ name: "My Notes" });
    expect(result.content[0].text).toBe("# My Notes\n- First block  ⟨b1⟩");
  });

  test("page_list renders a contract-shaped bridge result as pagination text", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({
      total: 1,
      offset: 0,
      pages: [{ name: "Notes" }],
    }));
    registerGraphTools(server, () => ({ bridge: makeMockBridge(sendRequest), db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["page_list"].handler({ pattern: "notes", limit: 10 });

    expect(sendRequest.mock.calls[0][0]).toBe("page_list");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ pattern: "notes", limit: 10 });
    expect(result.content[0].text).toBe("1 of 1 pages\n- Notes");
  });

  test("page_link sends properties_set with mode 'add' and wraps a single `to` string into an array", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ target: "from-page", properties: { related: ["To Page"] } }));
    registerGraphTools(server, () => ({ bridge: makeMockBridge(sendRequest), db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["page_link"].handler({ from: "From Page", to: "To Page" });

    expect(sendRequest.mock.calls[0][0]).toBe("properties_set");
    expect(sendRequest.mock.calls[0][1]).toEqual({
      page: "From Page",
      properties: { related: ["To Page"] },
      mode: "add",
    });
  });

  test("page_link with remove:true sends mode 'remove'", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ target: "from-page", properties: {} }));
    registerGraphTools(server, () => ({ bridge: makeMockBridge(sendRequest), db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["page_link"].handler({ from: "From Page", to: ["A", "B"], remove: true });

    expect(sendRequest.mock.calls[0][1]).toEqual({
      page: "From Page",
      properties: { related: ["A", "B"] },
      mode: "remove",
    });
  });

  test("block_insert with both page and uuid returns isError without calling the bridge", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({ uuids: [] }));
    registerGraphTools(server, () => ({ bridge: makeMockBridge(sendRequest), db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["block_insert"].handler({ content: "- a", page: "P", uuid: "u1" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("exactly one of page or uuid");
    expect(sendRequest.mock.calls.length).toBe(0);
  });

  test("block_insert with neither page nor uuid returns isError without calling the bridge", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({ uuids: [] }));
    registerGraphTools(server, () => ({ bridge: makeMockBridge(sendRequest), db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["block_insert"].handler({ content: "- a" });

    expect(result.isError).toBe(true);
    expect(sendRequest.mock.calls.length).toBe(0);
  });

  test("block_insert with exactly one of page|uuid calls the bridge", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ uuids: ["b1"] }));
    registerGraphTools(server, () => ({ bridge: makeMockBridge(sendRequest), db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["block_insert"].handler({ content: "- a", page: "P" });

    expect(sendRequest.mock.calls[0][0]).toBe("block_insert");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ page: "P", content: "- a" });
    expect(result.content[0].text).toBe(JSON.stringify({ uuids: ["b1"] }));
  });

  test("a bridge rejection surfaces as isError containing the rejection message", async () => {
    const server = createServer();
    const sendRequest = mock(async () => {
      throw new Error("Page not found: Ghost Page");
    });
    registerGraphTools(server, () => ({ bridge: makeMockBridge(sendRequest), db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["page_read"].handler({ name: "Ghost Page" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("Page not found: Ghost Page");
  });

  test("returns error when bridge is not connected", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({}));
    registerGraphTools(server, () => ({ bridge: makeMockBridge(sendRequest, false), db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["page_read"].handler({ name: "Test" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
    expect(sendRequest.mock.calls.length).toBe(0);
  });

  test("returns error when bridge is absent", async () => {
    const server = createServer();
    registerGraphTools(server, () => ({ db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["graph_query"].handler({ query: "[:find ?p]" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// Job tools
// ──────────────────────────────────────────────────────────────────────────────

describe("registerJobTools", () => {
  test("registers expected tool names", () => {
    const server = createServer();
    registerJobTools(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);
    expect("job_create" in tools).toBe(true);
    expect("job_list" in tools).toBe(true);
    expect("job_get" in tools).toBe(true);
    expect("job_start" in tools).toBe(true);
    expect("job_cancel" in tools).toBe(true);
    expect("job_pause" in tools).toBe(true);
    expect("job_resume" in tools).toBe(true);
    expect("skill_list" in tools).toBe(true);
    expect("skill_get" in tools).toBe(true);
    expect("skill_create" in tools).toBe(true);
  });

  test("job_create calls bridge.sendRequest with 'create_job' operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ jobId: "j1" }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerJobTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["job_create"].handler({ name: "my-job", type: "one-time" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("create_job");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ name: "my-job", type: "one-time" });
  });

  test("job_list calls bridge.sendRequest with 'list_jobs' operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => []);
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerJobTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["job_list"].handler({ status: "running", limit: 5 });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("list_jobs");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ status: "running", limit: 5 });
  });

  test("returns error when bridge is not connected", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({}));
    const mockBridge = {
      isPluginConnected: () => false,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerJobTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["job_create"].handler({ name: "j", type: "one-time" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
    expect(sendRequest.mock.calls.length).toBe(0);
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// Memory tools
// ──────────────────────────────────────────────────────────────────────────────

describe("registerMemoryTools", () => {
  test("registers expected tool names", () => {
    const server = createServer();
    registerMemoryTools(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);
    expect("memory_store" in tools).toBe(true);
    expect("memory_recall" in tools).toBe(true);
    expect("memory_search" in tools).toBe(true);
    expect("memory_list_tags" in tools).toBe(true);
  });

  test("memory_store calls bridge.sendRequest with 'store_memory' operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ ok: true }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerMemoryTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["memory_store"].handler({ tag: "work", content: "Important deadline" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("store_memory");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ tag: "work", content: "Important deadline" });
  });

  test("memory_recall calls bridge.sendRequest with 'recall_memory' operation", async () => {
    const server = createServer();
    const sendRequest = mock(async (_op: string, _params: any) => ({ entries: [] }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerMemoryTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["memory_recall"].handler({ tag: "work" });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("recall_memory");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({ tag: "work" });
  });

  test("returns error when bridge is not connected", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({}));
    const mockBridge = {
      isPluginConnected: () => false,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerMemoryTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["memory_recall"].handler({ tag: "work" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
    expect(sendRequest.mock.calls.length).toBe(0);
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// Messaging tools
// ──────────────────────────────────────────────────────────────────────────────

describe("registerMessagingTools", () => {
  test("registers expected tool names", () => {
    const server = createServer();
    registerMessagingTools(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);
    expect("message_send" in tools).toBe(true);
    expect("message_list" in tools).toBe(true);
    expect("contact_list" in tools).toBe(true);
  });

  test("contact_list queries DB directly and returns contacts", async () => {
    const server = createServer();
    const db = createTestDb();
    seedTestContact(db, "whatsapp", "15551234567", "Alice");
    seedTestContact(db, "telegram", "987654321", "Bob");

    registerMessagingTools(server, () => ({ db, config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["contact_list"].handler({});

    expect(result.isError).toBeUndefined();
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.count).toBe(2);
    expect(parsed.contacts.some((c: any) => c.displayName === "Alice")).toBe(true);
    expect(parsed.contacts.some((c: any) => c.displayName === "Bob")).toBe(true);
  });

  test("contact_list filters by platform", async () => {
    const server = createServer();
    const db = createTestDb();
    seedTestContact(db, "whatsapp", "15551234567", "Alice");
    seedTestContact(db, "telegram", "987654321", "Bob");

    registerMessagingTools(server, () => ({ db, config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["contact_list"].handler({ platform: "whatsapp" });

    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.count).toBe(1);
    expect(parsed.contacts[0].displayName).toBe("Alice");
  });

  test("contact_list returns empty list when no contacts exist", async () => {
    const server = createServer();
    registerMessagingTools(server, () => ({ db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["contact_list"].handler({});

    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.count).toBe(0);
    expect(parsed.contacts).toEqual([]);
  });
});
