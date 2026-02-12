import { loadConfig, validateConfig } from "./config";
import { getDatabase } from "./db/connection";
import { createRouter } from "./router";
import { sseManager } from "./services/sse";

const config = loadConfig();

const errors = validateConfig(config);
if (errors.length > 0) {
  console.error("Configuration errors:");
  errors.forEach((e) => console.error(`  - ${e}`));
  process.exit(1);
}

const db = getDatabase(config.databasePath);
const router = createRouter({ config, db });

sseManager.start();

const server = Bun.serve({
  port: config.port,
  fetch: router,
});

console.log(`Logseq AI Hub server running on port ${server.port}`);
