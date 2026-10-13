/** Connects an MCP client to the server the Logseq plugin is linked to. Shared by scripts/mcp.ts and tests/graph-live.test.ts. */
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

/** Base URL and token: LOGSEQ_AI_HUB_URL / LOGSEQ_AI_HUB_TOKEN, else the plugin's own settings. */
export function connection(): { base: string; token: string } {
  if (process.env.LOGSEQ_AI_HUB_URL && process.env.LOGSEQ_AI_HUB_TOKEN) {
    return { base: process.env.LOGSEQ_AI_HUB_URL, token: process.env.LOGSEQ_AI_HUB_TOKEN };
  }
  const settings = JSON.parse(readFileSync(join(homedir(), ".logseq", "settings", "logseq-ai-hub.json"), "utf8"));
  return { base: settings.webhookServerUrl, token: settings.pluginApiToken };
}

/** Throws unless the plugin is linked to `base` and running the main.js build on disk. */
export async function assertPluginCurrent(base: string): Promise<void> {
  const health = await (await fetch(`${base}/health`)).json();
  if (!health.agentApi?.pluginConnected) {
    throw new Error(`Plugin not linked to ${base}. Open Logseq (and reload the plugin after a build).`);
  }
  // Logseq loads main.js once, so a rebuild is invisible until the plugin reloads.
  // Results against an older build are meaningless; refuse rather than mislead.
  const onDisk = readFileSync(join(import.meta.dir, "..", "..", "main.js"), "utf8").match(/^window\.AI_HUB_BUILD="([^"]+)"/)?.[1];
  if (onDisk && health.agentApi.pluginBuild !== onDisk) {
    throw new Error(
      `Logseq is running plugin build ${health.agentApi.pluginBuild ?? "(unstamped)"}, but main.js on disk is ${onDisk}. ` +
        "Reload the plugin (Settings > Plugins > Logseq AI Hub > reload, or restart Logseq), then re-run.",
    );
  }
}

export async function connect(name: string): Promise<Client> {
  const { base, token } = connection();
  const client = new Client({ name, version: "1" });
  await client.connect(
    new StreamableHTTPClientTransport(new URL("/mcp", base), {
      requestInit: { headers: { Authorization: `Bearer ${token}` } },
    }),
  );
  return client;
}
