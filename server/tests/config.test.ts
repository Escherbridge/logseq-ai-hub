import { describe, test, expect, afterEach } from "bun:test";
import {
  loadConfig,
  parseLlmExtraHeaders,
  parseLlmRelayTokens,
  parseLlmRelayTimeoutSeconds,
  headerSafeEnv,
} from "../src/config";

const ENV_KEYS = [
  "BASE_URL",
  "RAILWAY_PUBLIC_DOMAIN",
  "PLUGIN_API_TOKEN",
  "LLM_API_KEY",
  "LLM_EXTRA_HEADERS",
  "LLM_RELAY_TOKENS",
  "LLM_RELAY_TIMEOUT_SECONDS",
] as const;
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

describe("parseLlmExtraHeaders", () => {
  test.each([
    [undefined, {}],
    ["", {}],
    ["   ", {}],
    ['{"api-key":"k"}', { "api-key": "k" }],
    ['{"authorization":"Custom x","api-key":"k"}', { authorization: "Custom x", "api-key": "k" }],
    ["{}", {}],
  ])("%p -> %p", (raw, expected) => {
    expect(parseLlmExtraHeaders(raw as string | undefined)).toEqual(expected as Record<string, string>);
  });

  // A typo must throw loudly at load time, not silently drop configured auth.
  test.each([
    ['{"api-key":', ],   // invalid JSON
    ["[]"],               // array, not object
    ["null"],              // null
    ['["api-key"]'],        // array of strings
    ['{"api-key": 1}'],     // non-string value
    ['{"api-key": "sk-1\\nX-Injected: y"}'], // CR/LF would make fetch() quote the value in its error
    ['{"api-key": "sk-1\\u0000X"}'], // NUL byte
    ['{"api-key": "sk-1\\u200bX"}'], // zero-width space falls outside the Latin-1 header range
    ['{"api-key": "   "}'], // blank value is not a credential
  ])("%p is rejected", (raw) => {
    expect(() => parseLlmExtraHeaders(raw)).toThrow(/LLM_EXTRA_HEADERS/);
  });

  test("a malformed value never echoes the credential it contains into the startup error", () => {
    let message = "";
    try {
      parseLlmExtraHeaders('{"api-key": "sk-live-secret-123"');
    } catch (e) {
      message = (e as Error).message;
    }
    expect(message).toMatch(/LLM_EXTRA_HEADERS/);
    expect(message).not.toContain("sk-live-secret-123");
  });

  test("a CR/LF header value never echoes the credential it contains into the startup error", () => {
    let message = "";
    try {
      parseLlmExtraHeaders('{"api-key": "sk-live-secret-123\\nX-Injected: y"}');
    } catch (e) {
      message = (e as Error).message;
    }
    expect(message).toMatch(/LLM_EXTRA_HEADERS/);
    expect(message).not.toContain("sk-live-secret-123");
  });
});

describe("parseLlmRelayTokens", () => {
  test.each([
    [undefined, "plugin-token", []],
    ["", "plugin-token", []],
    [" relay-token-one-1234 , ,relay-token-two-5678 ", "plugin-token", ["relay-token-one-1234", "relay-token-two-5678"]],
  ])("%p -> %p", (raw, pluginToken, expected) => {
    expect(parseLlmRelayTokens(raw as string | undefined, pluginToken as string)).toEqual(expected as string[]);
  });

  test("a token shorter than 16 characters throws, without echoing the token", () => {
    let message = "";
    try {
      parseLlmRelayTokens("short-token", "plugin-token-value");
    } catch (e) {
      message = (e as Error).message;
    }
    expect(message).toMatch(/LLM_RELAY_TOKENS/);
    expect(message).not.toContain("short-token");
  });

  test("a token equal to PLUGIN_API_TOKEN throws, without echoing the token", () => {
    const plugin = "the-plugin-api-token-value";
    let message = "";
    try {
      parseLlmRelayTokens(plugin, plugin);
    } catch (e) {
      message = (e as Error).message;
    }
    expect(message).toMatch(/LLM_RELAY_TOKENS/);
    expect(message).not.toContain(plugin);
  });
});

describe("headerSafeEnv", () => {
  test.each([
    ["\r\n"], // CR/LF
    ["\u0000"], // NUL
    ["​"], // zero-width space, outside the Latin-1 header-value range
  ])("rejects a value containing %p, without echoing the value", (badChar) => {
    let message = "";
    try {
      headerSafeEnv("LLM_API_KEY", `sk-secret-${badChar}value`);
    } catch (e) {
      message = (e as Error).message;
    }
    expect(message).toBe("LLM_API_KEY contains characters not allowed in an HTTP header");
    expect(message).not.toContain("sk-secret");
  });

  test("trims and passes through a clean value", () => {
    expect(headerSafeEnv("LLM_API_KEY", "  sk-abc123  ")).toBe("sk-abc123");
  });

  test("blank/empty -> \"\"", () => {
    expect(headerSafeEnv("LLM_API_KEY", "")).toBe("");
    expect(headerSafeEnv("LLM_API_KEY", "   ")).toBe("");
  });
});

describe("parseLlmRelayTimeoutSeconds", () => {
  test.each([
    [undefined, 110],
    ["", 110],
    ["10", 10],
    ["240", 240],
    ["110", 110],
  ])("%p -> %p", (raw, expected) => {
    expect(parseLlmRelayTimeoutSeconds(raw as string | undefined)).toBe(expected as number);
  });

  // Out of [10, 240], non-integer, or non-numeric must throw — a typo must not silently
  // become an unbounded wait or a too-short one that fails slow reasoning-model generations.
  test.each([["abc"], ["5"], ["999"], ["10.5"], ["-10"]])("%p is rejected", (raw) => {
    expect(() => parseLlmRelayTimeoutSeconds(raw)).toThrow(/LLM_RELAY_TIMEOUT_SECONDS/);
  });
});

// loadConfig() runs at top level in index.ts; a throw from any LLM-only parser used to
// crash-loop the whole server (MCP, SSE, messaging) over one typo in an optional LLM variable.
describe("loadConfig() LLM config errors are recorded, never thrown", () => {
  test.each([
    ["LLM_EXTRA_HEADERS", { LLM_EXTRA_HEADERS: '{"api-key": 1}' }, /LLM_EXTRA_HEADERS/],
    ["LLM_RELAY_TOKENS", { LLM_RELAY_TOKENS: "short-token" }, /LLM_RELAY_TOKENS/],
    ["LLM_API_KEY", { LLM_API_KEY: "sk-secret-测试-value" }, /LLM_API_KEY/],
    ["LLM_RELAY_TIMEOUT_SECONDS", { LLM_RELAY_TIMEOUT_SECONDS: "abc" }, /LLM_RELAY_TIMEOUT_SECONDS/],
  ])("an invalid %s does not throw; loadConfig() records it in llmConfigErrors instead", (_name, env, pattern) => {
    setEnv(env as Partial<Record<(typeof ENV_KEYS)[number], string>>);
    expect(() => loadConfig()).not.toThrow();
    const config = loadConfig();
    expect(config.llmConfigErrors.length).toBeGreaterThan(0);
    expect(config.llmConfigErrors.some((e) => (pattern as RegExp).test(e))).toBe(true);
  });

  test("an invalid LLM_API_KEY never echoes the raw secret into llmConfigErrors", () => {
    setEnv({ LLM_API_KEY: "sk-live-secret-999-测试" });
    const config = loadConfig();
    expect(config.llmConfigErrors.join(" ")).not.toContain("sk-live-secret-999");
  });

  test("an invalid LLM_RELAY_TIMEOUT_SECONDS falls back to the 110s default", () => {
    setEnv({ LLM_RELAY_TIMEOUT_SECONDS: "abc" });
    const config = loadConfig();
    expect(config.llmRelayTimeoutSeconds).toBe(110);
  });

  test("a clean LLM config loads with no errors", () => {
    setEnv({ LLM_API_KEY: "sk-clean-key", LLM_RELAY_TIMEOUT_SECONDS: "60" });
    const config = loadConfig();
    expect(config.llmConfigErrors).toEqual([]);
    expect(config.llmRelayTimeoutSeconds).toBe(60);
  });
});
