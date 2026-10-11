#!/usr/bin/env node
// Prefixes main.js with a content hash the plugin reports to the server on connect.
// /health exposes it as agentApi.pluginBuild, so `bun run test:live` can refuse to run
// against a Logseq that is still executing an older build (Logseq loads main.js once).
const fs = require("fs");
const crypto = require("crypto");

const PREFIX = "window.AI_HUB_BUILD=";
let source = fs.readFileSync("main.js", "utf8");
if (source.startsWith(PREFIX)) source = source.slice(source.indexOf("\n") + 1); // re-stamping replaces, never stacks
const build = crypto.createHash("sha256").update(source).digest("hex").slice(0, 12);
fs.writeFileSync("main.js", `${PREFIX}${JSON.stringify(build)};\n${source}`);
console.log(`stamped main.js as build ${build}`);
