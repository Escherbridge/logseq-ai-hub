import { loadConfig, validateConfig, validateAgentConfig } from "./config";
import { getDatabase } from "./db/connection";
import { createRouter } from "./router";
import { sseManager } from "./services/sse";
import { AgentBridge } from "./services/agent-bridge";
import { SessionStore } from "./services/session-store";
import { createMcpServer } from "./services/mcp-server";
import { registerAllMcpHandlers } from "./services/mcp/index";
import { ApprovalStore } from "./services/approval-store";
import { DynamicRegistry } from "./services/mcp/dynamic-registry";
import { SafeguardService } from "./services/safeguard-service";

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
const sessionStore = new SessionStore(db);
const approvalStore = new ApprovalStore();
const safeguardService = new SafeguardService(agentBridge, approvalStore);

// Initialize MCP server and register all tools/resources/prompts
const mcpServer = createMcpServer();
let dynamicRegistry: DynamicRegistry;
const getContext = () => ({
  bridge: agentBridge,
  db,
  config,
  approvalStore,
  dynamicRegistry,
  sessionStore,
  safeguardService,
});
registerAllMcpHandlers(mcpServer, getContext);
dynamicRegistry = new DynamicRegistry(mcpServer, getContext);

const router = createRouter({ config, db, agentBridge, sessionStore, approvalStore });

sseManager.start();

const server = Bun.serve({
  port: config.port,
  fetch: router,
});

console.log(`Logseq AI Hub server running on port ${server.port}`);
