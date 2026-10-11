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
