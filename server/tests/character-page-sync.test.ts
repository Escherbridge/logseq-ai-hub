import { describe, test, expect } from "bun:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerCharacterTools } from "../src/services/mcp/character-tools";
import { createCharacter } from "../src/db/characters";
import { createTestDb } from "./helpers";
import type { McpToolContext } from "../src/types/mcp";

/** Bridge fake that enforces the plugin's page_create contract: an existing page is an error unless if_exists says otherwise. */
function pageBridge() {
  const pages = new Set<string>();
  const appended: string[] = [];
  return {
    appended,
    isPluginConnected: () => true,
    async sendRequest(operation: string, params: any) {
      if (operation === "page_create") {
        const key = params.name.toLowerCase();
        if (pages.has(key)) {
          if ((params.if_exists ?? "error") === "error") throw new Error(`Page already exists: ${params.name}`);
          return { page: params.name, created: false, uuids: [] };
        }
        pages.add(key);
        return { page: params.name, created: true, uuids: [] };
      }
      if (operation === "block_append") {
        appended.push(params.content);
        return { page: params.page, blockUuid: "b" };
      }
      throw new Error(`unexpected operation ${operation}`);
    },
  };
}

describe("character_page_sync", () => {
  test("re-syncing an existing character page succeeds and does not duplicate the system prompt", async () => {
    const db = createTestDb();
    const character = createCharacter(db, { name: "Ada", system_prompt: "You are Ada." });
    const bridge = pageBridge();
    const server = new McpServer({ name: "t", version: "1" }, { capabilities: { tools: {} } });
    registerCharacterTools(server, () => ({ db, bridge } as unknown as McpToolContext));
    const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
    await server.connect(serverSide);
    const client = new Client({ name: "probe", version: "1" });
    await client.connect(clientSide);

    const first = await client.callTool({ name: "character_page_sync", arguments: { id: character.id } });
    const second = await client.callTool({ name: "character_page_sync", arguments: { id: character.id } });

    expect(first.isError).toBeFalsy();
    expect(second.isError).toBeFalsy();
    expect(bridge.appended).toEqual(["You are Ada."]);
    await client.close();
  });
});
