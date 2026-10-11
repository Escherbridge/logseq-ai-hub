/**
 * MCP from the command line, against the server the Logseq plugin is linked to.
 *   bun scripts/mcp.ts tools [regex]           list tools (name, params, description)
 *   bun scripts/mcp.ts call <tool> ['<json>']  call one tool and print its result
 * Exits non-zero when a call returns isError, so it composes in shell scripts.
 */
import { connect } from "./mcp-connection";

const [command, ...rest] = process.argv.slice(2);
const client = await connect("mcp-cli");
let failed = false;
try {
  if (command === "tools") {
    const filter = new RegExp(rest[0] ?? ".");
    for (const tool of (await client.listTools()).tools.filter((t) => filter.test(t.name))) {
      const params = Object.keys((tool.inputSchema as { properties?: object }).properties ?? {}).join(", ");
      console.log(`${tool.name}(${params})\n    ${tool.description ?? ""}`);
    }
  } else if (command === "call" && rest[0]) {
    const result = (await client.callTool({ name: rest[0], arguments: JSON.parse(rest[1] ?? "{}") })) as {
      content: { text?: string }[];
      isError?: boolean;
    };
    failed = Boolean(result.isError);
    console.log(result.content.map((c) => c.text ?? "").join("\n"));
  } else {
    console.error("usage: bun scripts/mcp.ts tools [regex] | call <tool> ['<json args>']");
    failed = true;
  }
} finally {
  await client.close();
}
process.exit(failed ? 1 : 0);
