import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { McpToolContext } from "../../types/mcp";
import {
  renderPage,
  renderBlock,
  renderSearch,
  renderLinks,
  renderPageList,
  type PageReadResult,
  type BlockGetResult,
  type SearchResult,
  type LinksResult,
  type PageListResult,
} from "./graph-render";

// String property values are written verbatim; string[] become [[page links]]
// (and so create backlinks); null (where allowed) removes the property.
const propertyValue = z.union([z.string(), z.array(z.string())]);
const propertyValueOrNull = z.union([z.string(), z.array(z.string()), z.null()]);

export function registerGraphTools(server: McpServer, getContext: () => McpToolContext): void {
  const errorResult = (message: string) => ({
    content: [{ type: "text" as const, text: `Error: ${message}` }],
    isError: true as const,
  });

  // Read tools get a `render` function and return markdown/text; write tools
  // omit it and get compact (non-pretty-printed) JSON -- see
  // `.omc/plans/graph-mcp-contract.md` for why text beats JSON for reads.
  async function bridgeTool<T = unknown>(
    operation: string,
    params: Record<string, unknown>,
    render?: (result: T) => string,
  ) {
    const ctx = getContext();
    if (!ctx.bridge?.isPluginConnected()) {
      return errorResult("Logseq plugin not connected");
    }
    try {
      const result = (await ctx.bridge.sendRequest(operation, params, ctx.traceId)) as T;
      const text = render ? render(result) : JSON.stringify(result);
      return { content: [{ type: "text" as const, text }] };
    } catch (err: any) {
      return errorResult(err.message);
    }
  }

  // ── Read tools ──────────────────────────────────────────────────────────

  server.tool(
    "graph_query",
    "Run a raw Datalog query against the Logseq graph. Prefer graph_search or page_list for common lookups; reach for this only when those can't express the query.",
    {
      query: z.string().describe("Datalog query string"),
      limit: z.number().int().min(1).max(1000).optional().describe("Max rows to return (default 100)"),
    },
    { readOnlyHint: true },
    async (params) => bridgeTool("graph_query", params),
  );

  server.tool(
    "graph_search",
    "Full-text search across all pages and blocks. Every whitespace-separated term in query must appear (any order, case-insensitive). Returns matching page titles plus blocks with snippets and ⟨uuid⟩ markers.",
    {
      query: z.string().describe("Search terms"),
      limit: z.number().int().min(1).max(100).optional().describe("Max blocks to return (default 20)"),
      page: z.string().optional().describe("Restrict the search to a single page"),
    },
    { readOnlyHint: true },
    async (params) => bridgeTool<SearchResult>("graph_search", params, renderSearch),
  );

  server.tool(
    "page_list",
    "List pages, optionally filtered by name pattern, namespace, tag, or property. Paginated -- when the output ends with 'Next page: offset N', pass that N back in to continue.",
    {
      pattern: z.string().optional().describe("Case-insensitive substring filter on page name"),
      namespace: z.string().optional().describe("Restrict to a namespace, e.g. 'Projects'"),
      tag: z.string().optional().describe("Restrict to pages tagged with this value"),
      property: z
        .object({ key: z.string(), value: z.string().optional() })
        .optional()
        .describe("Restrict to pages carrying this property (and optionally this value)"),
      journals: z
        .enum(["exclude", "include", "only"])
        .optional()
        .describe("How to handle journal pages (default exclude)"),
      sort: z.enum(["updated", "name"]).optional().describe("Sort order (default updated, newest first)"),
      limit: z.number().int().min(1).max(500).optional().describe("Max pages to return (default 50)"),
      offset: z.number().int().min(0).optional().describe("Pagination offset (default 0)"),
    },
    { readOnlyHint: true },
    async (params) => bridgeTool<PageListResult>("page_list", params, renderPageList),
  );

  server.tool(
    "page_read",
    "Read a page as an indented outline. Each block ends with ⟨uuid⟩ -- pass it to block_* tools; embed a block reference in content as ((uuid)). Links are [[Page]], tags #tag, properties `key:: value` lines.",
    {
      name: z.string().describe("Page name"),
      max_blocks: z
        .number()
        .int()
        .min(1)
        .optional()
        .describe("Truncate the outline after this many blocks (default 200); raise it or use block_get on a subtree instead of re-reading"),
      uuids: z.boolean().optional().describe("Show the ⟨uuid⟩ marker on each block (default true)"),
    },
    { readOnlyHint: true },
    async ({ name, max_blocks, uuids }) =>
      bridgeTool<PageReadResult>("page_read", { name }, (result) =>
        renderPage(result, { maxBlocks: max_blocks, showUuids: uuids }),
      ),
  );

  server.tool(
    "block_get",
    "Read a single block and its subtree as an indented outline, given its ⟨uuid⟩. Use this to drill into a branch that page_read truncated.",
    {
      uuid: z.string().describe("Block UUID"),
      max_blocks: z.number().int().min(1).optional().describe("Truncate the outline after this many blocks (default 200)"),
      uuids: z.boolean().optional().describe("Show the ⟨uuid⟩ marker on each block (default true)"),
    },
    { readOnlyHint: true },
    async ({ uuid, max_blocks, uuids }) =>
      bridgeTool<BlockGetResult>("block_get", { uuid }, (result) =>
        renderBlock(result, { maxBlocks: max_blocks, showUuids: uuids }),
      ),
  );

  server.tool(
    "page_links",
    "List a page's outgoing links and/or backlinks (other pages whose blocks reference it).",
    {
      name: z.string().describe("Page name"),
      direction: z
        .enum(["both", "in", "out"])
        .optional()
        .describe("'in' = backlinks only, 'out' = outgoing only (default both)"),
      limit: z.number().int().min(1).optional().describe("Max backlink blocks to return (default 50)"),
    },
    { readOnlyHint: true },
    async (params) => bridgeTool<LinksResult>("page_links", params, renderLinks),
  );

  // ── Write tools ─────────────────────────────────────────────────────────

  server.tool(
    "page_create",
    "Create a new page with optional markdown outline content and properties. String[] property values become [[links]] (and create backlinks on the linked pages).",
    {
      name: z.string().describe("Page name"),
      content: z.string().optional().describe('Initial outline content ("- " bullets, nested by indentation)'),
      properties: z.record(z.string(), propertyValue).optional().describe("Page properties"),
      if_exists: z
        .enum(["error", "skip", "append"])
        .optional()
        .describe("Behavior if the page already exists (default error)"),
    },
    async (params) => bridgeTool("page_create", params),
  );

  server.tool(
    "page_delete",
    "Delete a page and all of its blocks. [[References]] to it on other pages are left as-is, so the name lives on as an empty page -- use page_rename to repoint references instead.",
    { name: z.string().describe("Page name") },
    { destructiveHint: true },
    async (params) => bridgeTool("page_delete", params),
  );

  server.tool(
    "page_rename",
    "Rename a page. Updates every [[reference]] to it across the graph.",
    {
      name: z.string().describe("Current page name"),
      new_name: z.string().describe("New page name"),
    },
    async (params) => bridgeTool("page_rename", params),
  );

  server.tool(
    "block_insert",
    'Insert one or more blocks. content is a markdown outline ("- " bullets, nested by indentation → nested blocks). Target exactly one of page (position end|start) or uuid (position child = last child, before, after). Returns the new blocks\' uuids.',
    {
      content: z.string().describe("Markdown outline to insert"),
      page: z.string().optional().describe("Target page name (mutually exclusive with uuid)"),
      uuid: z.string().optional().describe("Target block UUID (mutually exclusive with page)"),
      position: z
        .enum(["end", "start", "child", "before", "after"])
        .optional()
        .describe("page target: 'end'(default)|'start'. uuid target: 'child'(default, last child)|'before'|'after'"),
    },
    async ({ page, uuid, ...rest }) => {
      if ((page == null) === (uuid == null)) {
        return errorResult("block_insert requires exactly one of page or uuid");
      }
      return bridgeTool("block_insert", { page, uuid, ...rest });
    },
  );

  server.tool(
    "block_update",
    "Update an existing block's content and/or properties by UUID. Provide at least one of content or properties. String[] property values become [[links]]; null removes a property.",
    {
      uuid: z.string().describe("Block UUID"),
      content: z.string().optional().describe("New block content"),
      properties: z.record(z.string(), propertyValueOrNull).optional().describe("Properties to write; null removes a key"),
    },
    async ({ uuid, content, properties }) => {
      if (content === undefined && properties === undefined) {
        return errorResult("block_update requires at least one of content or properties");
      }
      return bridgeTool("block_update", { uuid, content, properties });
    },
  );

  server.tool(
    "block_delete",
    "Delete one or more blocks by UUID. All-or-nothing: if any UUID doesn't exist, nothing is deleted. Deleting a block deletes its children too.",
    {
      uuid: z.string().optional().describe("Single block UUID to delete"),
      uuids: z.array(z.string()).optional().describe("Multiple block UUIDs to delete"),
    },
    { destructiveHint: true },
    async ({ uuid, uuids }) => {
      if (uuid === undefined && uuids === undefined) {
        return errorResult("block_delete requires uuid or uuids");
      }
      return bridgeTool("block_delete", { uuid, uuids });
    },
  );

  server.tool(
    "block_move",
    "Move an existing block under another block or onto a page. Target exactly one of target_uuid or page.",
    {
      uuid: z.string().describe("Block UUID to move"),
      target_uuid: z.string().optional().describe("Destination block UUID (mutually exclusive with page)"),
      page: z.string().optional().describe("Destination page name (mutually exclusive with target_uuid)"),
      position: z
        .enum(["after", "before", "child", "end", "start"])
        .optional()
        .describe("target_uuid: 'after'(default)|'before'|'child'. page: 'end'(default)|'start'"),
    },
    async ({ target_uuid, page, ...rest }) => {
      if ((target_uuid == null) === (page == null)) {
        return errorResult("block_move requires exactly one of target_uuid or page");
      }
      return bridgeTool("block_move", { target_uuid, page, ...rest });
    },
  );

  server.tool(
    "properties_set",
    "Set, add to, or remove page/block properties. Target exactly one of page or uuid. String[] values become [[links]] (creating backlinks); null removes a property. page_link is the shorthand for linking two pages.",
    {
      page: z.string().optional().describe("Target page name (mutually exclusive with uuid)"),
      uuid: z.string().optional().describe("Target block UUID (mutually exclusive with page)"),
      properties: z.record(z.string(), propertyValueOrNull).describe("Properties to write"),
      mode: z
        .enum(["set", "add", "remove"])
        .optional()
        .describe("'set'(default) replaces, 'add' merges into existing link lists, 'remove' removes the given values"),
    },
    async ({ page, uuid, ...rest }) => {
      if ((page == null) === (uuid == null)) {
        return errorResult("properties_set requires exactly one of page or uuid");
      }
      return bridgeTool("properties_set", { page, uuid, ...rest });
    },
  );

  server.tool(
    "page_link",
    "Connect two pages by adding a link property (default 'related') from one page to the other -- this is the way to wire up a navigable relationship between pages (it creates a backlink on the target too). Sugar over properties_set.",
    {
      from: z.string().describe("Source page name"),
      to: z.union([z.string(), z.array(z.string())]).describe("Target page name(s) to link to"),
      property: z.string().optional().describe("Property name to write the link(s) under (default 'related')"),
      remove: z.boolean().optional().describe("Remove the link(s) instead of adding them (default false)"),
    },
    async ({ from, to, property = "related", remove = false }) => {
      const toArray = Array.isArray(to) ? to : [to];
      return bridgeTool("properties_set", {
        page: from,
        properties: { [property]: toArray },
        mode: remove ? "remove" : "add",
      });
    },
  );

  server.tool(
    "journal_append",
    "Append a markdown outline to a daily journal page (today's by default). Fails if Logseq has not created that day's journal yet -- use page_create for other dates.",
    {
      content: z.string().describe("Markdown outline to append"),
      date: z.string().optional().describe("Journal date as YYYY-MM-DD (default today)"),
    },
    async (params) => bridgeTool("journal_append", params),
  );
}
