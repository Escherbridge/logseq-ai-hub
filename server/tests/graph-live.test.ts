/**
 * Live end-to-end test of the graph MCP tools: a real MCP client -> this server -> the
 * plugin running inside a real Logseq -> the user's graph. It is the source of truth the
 * CLJS fake host (src/test/.../fake_logseq.cljs) is corrected against.
 *
 * Skipped unless LIVE_LOGSEQ=1. Run: `bun run test:live` with Logseq open and the plugin
 * reloaded after a build. Server URL and token come from LOGSEQ_AI_HUB_URL /
 * LOGSEQ_AI_HUB_TOKEN, else from the plugin's own settings, so it exercises whichever
 * server the plugin is linked to. Every page lives under a unique AI-Hub-Live/<run>
 * namespace, deleted in afterAll.
 */
import { describe, test, expect, beforeAll, afterAll } from "bun:test";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { assertPluginCurrent } from "../scripts/mcp-connection";

const live = process.env.LIVE_LOGSEQ === "1";
const TIMEOUT = 30_000;

function connection(): { base: string; token: string } {
  if (process.env.LOGSEQ_AI_HUB_URL && process.env.LOGSEQ_AI_HUB_TOKEN) {
    return { base: process.env.LOGSEQ_AI_HUB_URL, token: process.env.LOGSEQ_AI_HUB_TOKEN };
  }
  const settings = JSON.parse(readFileSync(join(homedir(), ".logseq", "settings", "logseq-ai-hub.json"), "utf8"));
  return { base: settings.webhookServerUrl, token: settings.pluginApiToken };
}

const RUN = `AI-Hub-Live/r${Date.now().toString(36)}`;
const page = (name: string) => `${RUN}/${name}`;
const MARK = `livemark${Date.now().toString(36)}`; // unique search term, so the user's graph never matches
let client: Client;

async function call(name: string, args: Record<string, unknown>) {
  const result = (await client.callTool({ name, arguments: args })) as { content: { text: string }[]; isError?: boolean };
  return { text: result.content.map((c) => c.text).join("\n"), isError: Boolean(result.isError) };
}
async function ok(name: string, args: Record<string, unknown>) {
  const r = await call(name, args);
  if (r.isError) throw new Error(`${name} failed: ${r.text}`);
  return r.text;
}
const json = async (name: string, args: Record<string, unknown>) => JSON.parse(await ok(name, args));

/** uuid of the outline line whose text starts with `content`. */
function uuidOf(outline: string, content: string): string {
  const line = outline.split("\n").find((l) => l.trimStart().startsWith(`- ${content}`));
  const match = line?.match(/⟨([0-9a-f-]{36})⟩/);
  if (!match) throw new Error(`no block "${content}" in:\n${outline}`);
  return match[1];
}
/** Block lines of an outline without uuid markers, e.g. ["- Alpha", "  - Alpha child"]. */
const shape = (outline: string) =>
  outline.split("\n").filter((l) => /^\s*- /.test(l)).map((l) => l.replace(/\s+⟨[0-9a-f-]{36}⟩$/, ""));

describe.skipIf(!live)("graph tools against a live Logseq", () => {
  beforeAll(async () => {
    const { base, token } = connection();
    await assertPluginCurrent(base);
    client = new Client({ name: "graph-live-test", version: "1" });
    await client.connect(
      new StreamableHTTPClientTransport(new URL("/mcp", base), {
        requestInit: { headers: { Authorization: `Bearer ${token}` } },
      }),
    );
  });

  afterAll(async () => {
    if (!client) return;
    const listing = await call("page_list", { namespace: RUN, limit: 500 });
    for (const name of listing.text.split("\n").filter((l) => l.startsWith("- ")).map((l) => l.slice(2).split(" · ")[0])) {
      await call("page_delete", { name });
    }
    // Logseq creates a page for every namespace level; drop ours, and the root once no run remains.
    await call("page_delete", { name: RUN });
    if ((await call("page_list", { namespace: "AI-Hub-Live" })).text.startsWith("0 of 0")) {
      await call("page_delete", { name: "AI-Hub-Live" });
    }
    await client.close();
  });

  test("creates a page from a nested outline with page properties", async () => {
    const created = await json("page_create", {
      name: page("Alpha"),
      content: "- Alpha\n  - Alpha child\n- Beta links [[" + page("Beta") + "]]",
      properties: { tags: [page("tag")] },
    });
    expect(created.created).toBe(true);
    expect(created.uuids).toHaveLength(3);

    const outline = await ok("page_read", { name: page("Alpha") });
    expect(outline.split("\n")[1]).toBe(`tags:: [[${page("tag")}]]`); // a page property: first line, no bullet
    expect(shape(outline)).toEqual(["- Alpha", "  - Alpha child", `- Beta links [[${page("Beta")}]]`]);
  }, TIMEOUT);

  test("a page that exists only because of a [[link]] is written, not skipped", async () => {
    const created = await json("page_create", { name: page("Beta"), content: `- Gamma ${MARK}`, if_exists: "skip" });
    expect(created.created).toBe(true);
    expect(shape(await ok("page_read", { name: page("Beta") }))).toEqual([`- Gamma ${MARK}`]);
  }, TIMEOUT);

  test("page_link merges into the link list and shows up as a backlink", async () => {
    await ok("page_link", { from: page("Beta"), to: page("Alpha") });
    const second = await json("page_link", { from: page("Beta"), to: [page("Delta"), page("alpha")] });
    expect(second.properties.related).toBe(`[[${page("Alpha")}]], [[${page("Delta")}]]`); // no case duplicate, no [object Object]

    const outline = await ok("page_read", { name: page("Beta") });
    expect(outline.split("\n")[1]).toBe(`related:: [[${page("Alpha")}]], [[${page("Delta")}]]`);
    expect(await ok("page_links", { name: page("Alpha"), direction: "in" })).toContain(`[[${page("Beta")}]]`);
  }, TIMEOUT);

  test("nothing can be inserted before a page's properties block", async () => {
    const props = await json("properties_set", { page: page("Beta"), properties: { status: "live" } });
    const refused = await call("block_insert", { uuid: props.target, position: "before", content: "x" });
    expect(refused.isError).toBe(true);
    expect(refused.text).toContain("properties block");
  }, TIMEOUT);

  test("inserts land at exact positions and a move re-parents the block", async () => {
    let outline = await ok("page_read", { name: page("Alpha") });
    await ok("block_insert", { uuid: uuidOf(outline, "Alpha"), content: "- Alpha second" });
    await ok("block_insert", { uuid: uuidOf(outline, "Beta links"), position: "before", content: "Before beta" });
    outline = await ok("page_read", { name: page("Alpha") });
    await ok("block_move", { uuid: uuidOf(outline, "Alpha second"), target_uuid: uuidOf(outline, "Beta links"), position: "child" });

    expect(shape(await ok("page_read", { name: page("Alpha") }))).toEqual([
      "- Alpha",
      "  - Alpha child",
      "- Before beta",
      `- Beta links [[${page("Beta")}]]`,
      "  - Alpha second",
    ]);
  }, TIMEOUT);

  test("search requires every term and can be scoped to a page", async () => {
    expect(await ok("graph_search", { query: `gamma ${MARK.toUpperCase()}` })).toContain("showing 1 of 1");
    expect((await call("graph_search", { query: MARK, page: page("Nope") })).text).toContain("Page not found");
  }, TIMEOUT);

  test("a batch delete with one missing uuid deletes nothing", async () => {
    const outline = await ok("page_read", { name: page("Alpha") });
    const beta = uuidOf(outline, "Beta links");
    const refused = await call("block_delete", { uuids: [beta, "00000000-0000-0000-0000-000000000000"] });
    expect(refused.isError).toBe(true);
    expect(await ok("page_read", { name: page("Alpha") })).toContain(beta);

    const deleted = await json("block_delete", { uuids: [beta] });
    expect(deleted.deleted).toEqual([expect.objectContaining({ uuid: beta, descendants: 1 })]);
  }, TIMEOUT);

  test("rename updates references and refuses to merge into an existing page", async () => {
    expect((await call("page_rename", { name: page("Alpha"), new_name: page("Beta") })).isError).toBe(true);
    await ok("page_rename", { name: page("Alpha"), new_name: page("Alpha renamed") });
    expect(await ok("page_read", { name: page("Beta") })).toContain(`[[${page("Alpha renamed")}]]`);
  }, TIMEOUT);
});
