import { describe, test, expect } from "bun:test";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { InMemoryTransport } from "@modelcontextprotocol/sdk/inMemory.js";
import { createBareMcpServer } from "../src/services/mcp-server";
import { registerGraphTools } from "../src/services/mcp/graph-tools";
import type { McpToolContext } from "../src/types/mcp";

describe("server instructions", () => {
  test("a connecting client receives instructions that only name tools which exist", async () => {
    const server = createBareMcpServer();
    registerGraphTools(server, () => ({ bridge: undefined } as unknown as McpToolContext));
    const [clientSide, serverSide] = InMemoryTransport.createLinkedPair();
    await server.connect(serverSide);
    const client = new Client({ name: "probe", version: "1" });
    await client.connect(clientSide);

    const instructions = client.getInstructions() ?? "";
    expect(instructions).toContain("⟨uuid⟩");

    const toolNames = new Set((await client.listTools()).tools.map((t) => t.name));
    const mentioned = [...new Set(instructions.match(/\b[a-z]+_[a-z_]+\b/g) ?? [])]
      .filter((word) => !["if_exists", "max_blocks"].includes(word)); // parameters, not tools
    expect(mentioned.length).toBeGreaterThan(5);
    expect(mentioned.filter((name) => !toolNames.has(name))).toEqual([]);

    await client.close();
  });
});
