import { describe, test, expect, afterEach } from "bun:test";
import { loadConfig } from "../src/config";

const ENV_KEYS = ["BASE_URL", "RAILWAY_PUBLIC_DOMAIN"] as const;
const saved = new Map<string, string | undefined>();

function setEnv(values: Partial<Record<(typeof ENV_KEYS)[number], string>>) {
  for (const k of ENV_KEYS) {
    if (!saved.has(k)) saved.set(k, process.env[k]);
    const v = values[k];
    if (v === undefined) delete process.env[k];
    else process.env[k] = v;
  }
}

afterEach(() => {
  for (const [k, v] of saved) {
    if (v === undefined) delete process.env[k];
    else process.env[k] = v;
  }
  saved.clear();
});

// config.baseUrl is read by approval links, messaging links and MCP discovery.
// It was referenced but never populated, so production emitted localhost URLs.
describe("loadConfig().baseUrl", () => {
  test("promotes Railway's scheme-less public domain to an https origin", () => {
    setEnv({ RAILWAY_PUBLIC_DOMAIN: "logseq-ai-hub-production.up.railway.app" });
    expect(loadConfig().baseUrl).toBe(
      "https://logseq-ai-hub-production.up.railway.app",
    );
  });

  test("BASE_URL overrides the Railway domain", () => {
    setEnv({
      BASE_URL: "https://hub.example.com",
      RAILWAY_PUBLIC_DOMAIN: "logseq-ai-hub-production.up.railway.app",
    });
    expect(loadConfig().baseUrl).toBe("https://hub.example.com");
  });

  test("trailing slashes are stripped so joined paths get one separator", () => {
    setEnv({ BASE_URL: "https://hub.example.com/" });
    expect(`${loadConfig().baseUrl}/mcp`).toBe("https://hub.example.com/mcp");
  });

  test("is empty when neither var is set, leaving callers' localhost fallback intact", () => {
    setEnv({});
    expect(loadConfig().baseUrl).toBe("");
  });
});

describe("parseHttpAllowlist", () => {
  const { parseHttpAllowlist } = require("../src/config") as typeof import("../src/config");

  test.each([
    [undefined, []],
    ["", []],
    ["[]", []],
    ['["api.github.com", "*.stripe.com"]', ["api.github.com", "*.stripe.com"]],
    ["api.github.com, *.stripe.com", ["api.github.com", "*.stripe.com"]],
  ])("%p -> %p", (raw, expected) => {
    expect(parseHttpAllowlist(raw as string | undefined)).toEqual(expected as string[]);
  });

  // A typo must stop the server, not quietly mean "any host".
  test.each([['["api.github.com"'], ['"api.github.com"'], ['[1, 2]'], ['{"host": "a.com"}'], ["api github com"]])(
    "%p is rejected",
    (raw) => {
      expect(() => parseHttpAllowlist(raw)).toThrow(/HTTP_ALLOWLIST/);
    },
  );
});
