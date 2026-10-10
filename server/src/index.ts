import { loadConfig, validateConfig, validateAgentConfig } from "./config";
import { getDatabase } from "./db/connection";
import { createRouter } from "./router";
import { sseManager, HEARTBEAT_SECONDS } from "./services/sse";
import { AgentBridge } from "./services/agent-bridge";
import { SessionStore } from "./services/session-store";
import {
  createMcpServer,
  createBareMcpServer,
  registerSessionServerFactory,
} from "./services/mcp-server";
import { registerAllMcpHandlers } from "./services/mcp/index";
import { ApprovalStore } from "./services/approval-store";
import { DynamicRegistry } from "./services/mcp/dynamic-registry";
import { SafeguardService } from "./services/safeguard-service";
import { WorkClaimStore } from "./services/work-store";
import { PiDevManager } from "./services/pidev-manager";
import { EventBus } from "./services/event-bus";

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
const eventBus = new EventBus(db);
const agentBridge = new AgentBridge(config.agentRequestTimeout);
const sessionStore = new SessionStore(db);
const approvalStore = new ApprovalStore();
const safeguardService = new SafeguardService(agentBridge, approvalStore);
const workStore = new WorkClaimStore();
const piDevManager = new PiDevManager(agentBridge, {
  enabled: false, // Disabled by default — users enable via plugin settings
  installPath: "",
  defaultModel: "anthropic/claude-sonnet-4",
  rpcPort: 0,
  maxConcurrentSessions: 3,
});

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
  workStore,
  piDevManager,
  eventBus,
  sseManager,
});
registerAllMcpHandlers(mcpServer, getContext);
dynamicRegistry = new DynamicRegistry(mcpServer, getContext);

// Every MCP session needs its own McpServer instance (the SDK forbids
// attaching a second transport to an already-connected Protocol) but must
// still see the full 104-tool static catalogue plus any dynamic KB tools
// registered so far. This factory builds that per-session server and
// attaches it to the shared DynamicRegistry so future syncFromBridge()
// notifications reach it too; mcp-transport.ts calls it per new session
// and disposes it (detach + close) on teardown.
registerSessionServerFactory(() => {
  const sessionServer = createBareMcpServer();
  registerAllMcpHandlers(sessionServer, getContext);
  dynamicRegistry.attach(sessionServer);

  return {
    server: sessionServer,
    dispose: () => {
      dynamicRegistry.detach(sessionServer);
      sessionServer.close().catch(() => {
        /* transport already closing/closed — nothing to do */
      });
    },
  };
});

const router = createRouter({ config, db, agentBridge, sessionStore, approvalStore, eventBus });

sseManager.start();

// Prune old events daily
setInterval(() => eventBus.prune(config.eventRetentionDays), 24 * 60 * 60 * 1000);

const server = Bun.serve({
  port: config.port,
  fetch: router,
  // Bun closes connections idle for 10s by default, which cut every SSE stream
  // between 15s heartbeats and made the plugin link flap. Keep it well above the beat.
  idleTimeout: HEARTBEAT_SECONDS * 8,
});

console.log(`Logseq AI Hub server running on port ${server.port}`);
