import { loadConfig, validateConfig, validateAgentConfig } from "./config";
import { getDatabase } from "./db/connection";
import { createRouter } from "./router";
import { sseManager } from "./services/sse";
import { AgentBridge } from "./services/agent-bridge";
import { ConversationStore } from "./services/conversations";
import { createMcpServer } from "./services/mcp-server";
import { registerAllMcpHandlers } from "./services/mcp/index";

const config = loadConfig();

const errors = validateConfig(config);
if (errors.length > 0) {
  console.error("Configuration errors:");
  errors.forEach((e) => console.error(`  - ${e}`));
  process.exit(1);
}

const agentWarnings = validateAgentConfig(config);
agentWarnings.forEach((w) => console.warn(`  Warning: ${w}`));

const db = getDatabase(config.databasePath);
const agentBridge = new AgentBridge(config.agentRequestTimeout);
const conversations = new ConversationStore();

// Initialize MCP server and register all tools/resources/prompts
const mcpServer = createMcpServer();
registerAllMcpHandlers(mcpServer, () => ({
  bridge: agentBridge,
  db,
  config,
}));

const router = createRouter({ config, db, agentBridge, conversations });

sseManager.start();

const server = Bun.serve({
  port: config.port,
  fetch: router,
});

console.log(`Logseq AI Hub server running on port ${server.port}`);
