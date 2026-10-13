/**
 * Live end-to-end test of the LLM transports: real HTTP -> this server's /api/llm relay -> the
 * real gateway, and a real job whose llm-call step runs inside the plugin in a real Logseq,
 * through whichever `llmTransport` the plugin is set to, against the real provider.
 *
 * Skipped unless LIVE_LOGSEQ=1. Run: `bun run test:live` with Logseq open and the plugin
 * reloaded after a build. Spends a few completion tokens per run. To cover every transport,
 * re-run after switching Settings > Logseq AI Hub > LLM Transport. The relay's request counter
 * on /health proves which path the plugin took. Job and skill pages are deleted in afterAll.
 */
import { describe, test, expect, beforeAll, afterAll } from "bun:test";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import type { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { assertPluginCurrent, connect, connection } from "../scripts/mcp-connection";

const live = process.env.LIVE_LOGSEQ === "1";
const RUN = `r${Date.now().toString(36)}`;
const NONCE = `E2E-${RUN}-${Math.random().toString(36).slice(2, 8)}`.toUpperCase();
const SKILL = `Skills/AI-Hub-Live-${RUN}-echo`;
const JOB = `Jobs/AI-Hub-Live-${RUN}-job`;
const ECHO_PROMPT = "Reply with exactly this token and nothing else: ";

let client: Client;
const { base, token } = live ? connection() : { base: "", token: "" };

function pluginTransport(): string {
  const settings = JSON.parse(readFileSync(join(homedir(), ".logseq", "settings", "logseq-ai-hub.json"), "utf8"));
  return typeof settings.llmTransport === "string" && settings.llmTransport.trim() ? settings.llmTransport.trim() : "direct";
}

async function relayRequests(): Promise<number> {
  const health = await (await fetch(`${base}/health`)).json();
  return health.llmRelay?.requests ?? Number.NaN;
}

async function tool(name: string, args: Record<string, unknown>): Promise<string> {
  const result = (await client.callTool({ name, arguments: args })) as { content: { text: string }[]; isError?: boolean };
  const text = result.content.map((c) => c.text).join("\n");
  if (result.isError) throw new Error(`${name} failed: ${text}`);
  return text;
}

describe.skipIf(!live)("LLM transports against a live Logseq and a real provider", () => {
  beforeAll(async () => {
    await assertPluginCurrent(base);
    client = await connect("llm-live-test");
  });

  afterAll(async () => {
    if (!client) return;
    for (const name of [JOB, SKILL]) {
      await client.callTool({ name: "page_delete", arguments: { name } }).catch(() => undefined);
    }
    await client.close();
  });

  test("the relay returns a real completion from the server's own gateway", async () => {
    const res = await fetch(`${base}/api/llm/chat/completions`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ messages: [{ role: "user", content: ECHO_PROMPT + NONCE }] }),
    });
    const body = await res.json();
    expect(res.status).toBe(200);
    expect(String(body.choices?.[0]?.message?.content ?? "").toUpperCase()).toContain(NONCE);
  }, 120_000);

  test("the relay lists the gateway's models and refuses a wrong token", async () => {
    const models = await fetch(`${base}/api/llm/models`, { headers: { Authorization: `Bearer ${token}` } });
    expect(models.status).toBe(200);
    expect(Array.isArray((await models.json()).data)).toBe(true);

    const denied = await fetch(`${base}/api/llm/models`, { headers: { Authorization: "Bearer not-the-token" } });
    expect(denied.status).toBe(401);
    expect(await denied.json()).toEqual({ success: false, error: "Unauthorized" }); // the shape the doctor matches
  }, 60_000);

  // Needs LOGSEQ_AI_HUB_RELAY_TOKEN set to one of the server's LLM_RELAY_TOKENS.
  test.skipIf(!process.env.LOGSEQ_AI_HUB_RELAY_TOKEN)("a relay-only token reaches the relay and nothing else", async () => {
    const auth = { Authorization: `Bearer ${process.env.LOGSEQ_AI_HUB_RELAY_TOKEN}` };
    expect((await fetch(`${base}/api/llm/models`, { headers: auth })).status).toBe(200);

    const mcp = await fetch(`${base}/mcp`, {
      method: "POST",
      headers: { ...auth, "Content-Type": "application/json", Accept: "application/json, text/event-stream" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "relay-probe", version: "1" } } }),
    });
    expect(mcp.status).toBe(401);
    const send = await fetch(`${base}/api/send`, {
      method: "POST",
      headers: { ...auth, "Content-Type": "application/json" },
      body: JSON.stringify({ platform: "telegram", recipient: "x", content: "x" }),
    });
    expect(send.status).toBe(401);
  }, 60_000);

  test("a job's llm-call step completes through the plugin's configured transport", async () => {
    const transport = pluginTransport();
    const before = await relayRequests();

    await tool("skill_create", {
      name: SKILL.replace(/^Skills\//, ""),
      type: "llm-chain",
      description: "Live E2E: echo a nonce through the configured LLM transport",
      inputs: ["nonce"],
      outputs: ["reply"],
      steps: [{ order: 1, action: "llm-call", prompt_template: `${ECHO_PROMPT}{{nonce}}` }],
    });
    await tool("job_create", { name: JOB.replace(/^Jobs\//, ""), type: "manual", skill: SKILL, input: { nonce: NONCE } });
    await tool("job_start", { jobId: JOB });

    let job: { status: string; result?: string; error?: string } = { status: "queued" };
    const deadline = Date.now() + 150_000;
    while (!["completed", "failed", "cancelled"].includes(job.status) && Date.now() < deadline) {
      await Bun.sleep(2_000);
      job = JSON.parse(await tool("job_get", { jobId: JOB }));
    }

    expect({ transport, status: job.status, error: job.error ?? null }).toEqual({ transport, status: "completed", error: null });
    expect(String(job.result).toUpperCase()).toContain(NONCE);

    const after = await relayRequests();
    if (transport === "hub-server") {
      expect(after).toBeGreaterThan(before); // the plugin's call went through this server's relay
    } else {
      expect(after).toBe(before); // direct / logseq-desktop must not touch the relay
    }
  }, 180_000);
});
