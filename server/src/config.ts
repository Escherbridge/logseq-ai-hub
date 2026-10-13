export interface Config {
  port: number;
  whatsappVerifyToken: string;
  whatsappAccessToken: string;
  whatsappPhoneNumberId: string;
  telegramBotToken: string;
  pluginApiToken: string;
  databasePath: string;
  llmApiKey: string;
  llmEndpoint: string;
  agentModel: string;
  agentRequestTimeout: number;
  sessionMessageLimit: number;
  eventRetentionDays: number;
  httpAllowlist: string[];
  listLimitMax: number;
  webhookSecret: string;
  llmHttpReferer: string;
  llmTitle: string;
  /** Extra headers merged onto every upstream LLM request; see parseLlmExtraHeaders. */
  llmExtraHeaders: Record<string, string>;
  /** Public origin used to build outbound links (approvals, MCP discovery). "" when unknown. */
  baseUrl: string;
  /** Tokens that unlock ONLY /api/llm/*, never /mcp or /api/send; see parseLlmRelayTokens. */
  llmRelayTokens: string[];
  /** Models the relay will forward to upstream; empty means any model. */
  llmAllowedModels: string[];
  /** Seconds the relay waits for the upstream gateway before 504; see parseLlmRelayTimeoutSeconds. */
  llmRelayTimeoutSeconds: number;
  /**
   * Startup failures from LLM-only env parsing (never echoes raw values). Non-empty means the
   * server came up but LLM features are disabled — see hasLlmCredentials — instead of the whole
   * process crash-looping over a typo in one optional variable.
   */
  llmConfigErrors: string[];
}

/**
 * Public origin for outbound links. BASE_URL wins; otherwise Railway's
 * RAILWAY_PUBLIC_DOMAIN (injected scheme-less) is promoted to https.
 * Returns "" when neither is set, so callers keep their localhost fallback.
 */
function resolveBaseUrl(): string {
  const stripTrailingSlashes = (s: string) => s.replace(/\/+$/, "");

  const explicit = (process.env.BASE_URL || "").trim();
  if (explicit) return stripTrailingSlashes(explicit);

  const railwayDomain = (process.env.RAILWAY_PUBLIC_DOMAIN || "").trim();
  if (railwayDomain) {
    const host = stripTrailingSlashes(railwayDomain.replace(/^https?:\/\//, ""));
    return host ? `https://${host}` : "";
  }

  return "";
}

/**
 * HTTP_ALLOWLIST: a JSON array of host patterns, or a comma-separated list. Empty means any host.
 * Anything else throws: a typo must never silently become "allow all".
 */
export function parseHttpAllowlist(raw: string | undefined): string[] {
  const value = (raw ?? "").trim();
  if (!value) return [];
  if (value.startsWith("[")) {
    let parsed: unknown;
    try {
      parsed = JSON.parse(value);
    } catch {
      throw new Error(`HTTP_ALLOWLIST is not valid JSON: ${value}`);
    }
    if (!Array.isArray(parsed) || !parsed.every((p) => typeof p === "string" && p.trim())) {
      throw new Error(`HTTP_ALLOWLIST must be an array of host patterns, got: ${value}`);
    }
    return parsed.map((p: string) => p.trim());
  }
  if (/[\s{}"]/.test(value.replace(/,\s+/g, ","))) {
    throw new Error(`HTTP_ALLOWLIST must be a JSON array or comma-separated hosts, got: ${value}`);
  }
  return value.split(",").map((p) => p.trim()).filter(Boolean);
}

/**
 * LLM_EXTRA_HEADERS: a JSON object of extra headers merged onto every upstream LLM
 * request (e.g. Azure's `api-key`, or a custom `authorization`). Empty/unset -> {}.
 * Anything else throws: a typo must never silently drop configured auth.
 */
export function parseLlmExtraHeaders(raw: string | undefined): Record<string, string> {
  const value = (raw ?? "").trim();
  if (!value) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    // Never echo the raw value: it holds gateway credentials and startup errors reach host logs.
    throw new Error("LLM_EXTRA_HEADERS is not valid JSON");
  }
  if (
    typeof parsed !== "object" ||
    parsed === null ||
    Array.isArray(parsed)
  ) {
    throw new Error("LLM_EXTRA_HEADERS must be a JSON object of header names to string values");
  }
  const result: Record<string, string> = {};
  for (const [k, v] of Object.entries(parsed as Record<string, unknown>)) {
    if (typeof v !== "string") {
      throw new Error(`LLM_EXTRA_HEADERS value for "${k}" must be a string, got ${typeof v}`);
    }
    if (!HEADER_NAME.test(k)) {
      throw new Error(`LLM_EXTRA_HEADERS has an invalid header name "${k}"`);
    }
    // fetch() rejects a bad value with an error that quotes it, which would leak the credential.
    if (!HEADER_VALUE.test(v) || !v.trim()) {
      throw new Error(`LLM_EXTRA_HEADERS value for "${k}" is blank or contains characters not allowed in an HTTP header`);
    }
    result[k] = v.trim();
  }
  return result;
}

/**
 * True when upstream LLM calls can authenticate: an API key or any extra header, AND the LLM
 * config loaded cleanly. A startup error in one LLM variable (e.g. LLM_RELAY_TOKENS) must not
 * leave LLM half-configured with some settings dropped and others still live.
 */
export function hasLlmCredentials(
  config: Pick<Config, "llmApiKey" | "llmExtraHeaders" | "llmConfigErrors">
): boolean {
  if (config.llmConfigErrors.length > 0) return false;
  return Boolean(config.llmApiKey) || Object.keys(config.llmExtraHeaders).length > 0;
}

/** HTTP header field-value bytes: tab, visible Latin-1, no CR/LF/NUL. */
const HEADER_VALUE = /^[\t\x20-\x7e\x80-\xff]*$/;
/** HTTP header field-name token characters. */
const HEADER_NAME = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;

/**
 * Trims a header-bound env var and rejects characters fetch() would otherwise reject with an
 * error that quotes the raw value (CR/LF/NUL/etc). Throws naming the VARIABLE only — never the
 * value, which may be a credential (LLM_API_KEY, LLM_HTTP_REFERER, LLM_TITLE).
 */
export function headerSafeEnv(name: string, raw: string): string {
  const value = raw.trim();
  if (value && !HEADER_VALUE.test(value)) {
    throw new Error(`${name} contains characters not allowed in an HTTP header`);
  }
  return value;
}

/**
 * LLM_RELAY_TOKENS: comma-separated tokens that unlock ONLY the /api/llm/* relay — never
 * /mcp (graph read/write/delete, secrets) or /api/send (the bridge broadcasts MCP requests
 * to every SSE-linked plugin). Org users must get one of these, never PLUGIN_API_TOKEN.
 * Each entry must be >=16 chars and distinct from PLUGIN_API_TOKEN (equal would defeat the
 * point: a leaked relay token would then unlock everything). Neither error echoes the token.
 */
export function parseLlmRelayTokens(raw: string | undefined, pluginApiToken: string): string[] {
  const tokens = (raw ?? "")
    .split(",")
    .map((t) => t.trim())
    .filter(Boolean);
  for (const token of tokens) {
    if (token.length < 16) {
      throw new Error("LLM_RELAY_TOKENS entries must be at least 16 characters");
    }
    if (token === pluginApiToken) {
      throw new Error("LLM_RELAY_TOKENS must not contain PLUGIN_API_TOKEN");
    }
  }
  return tokens;
}

/** LLM_ALLOWED_MODELS: comma-separated model allowlist for the relay. Empty means any model. */
export function parseLlmAllowedModels(raw: string | undefined): string[] {
  return (raw ?? "")
    .split(",")
    .map((m) => m.trim())
    .filter(Boolean);
}

/** Fallback when LLM_RELAY_TIMEOUT_SECONDS is unset or invalid. */
export const DEFAULT_LLM_RELAY_TIMEOUT_SECONDS = 110;

/**
 * LLM_RELAY_TIMEOUT_SECONDS: how long the relay waits for the upstream gateway before
 * returning 504. Must be an integer in [10, 240] — wide enough for a slow non-streamed
 * reasoning-model generation, bounded well under Bun's 255s idleTimeout ceiling once the
 * +10s margin (see services/sse.ts idleTimeoutSeconds) is added back on top.
 */
export function parseLlmRelayTimeoutSeconds(raw: string | undefined): number {
  const value = (raw ?? "").trim();
  if (!value) return DEFAULT_LLM_RELAY_TIMEOUT_SECONDS;
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 10 || parsed > 240) {
    throw new Error("LLM_RELAY_TIMEOUT_SECONDS must be an integer between 10 and 240");
  }
  return parsed;
}

/**
 * Runs an LLM-only env parse; on failure records the message in `errors` (the parsers above
 * never echo raw values, so this is always safe to log/print) and returns `fallback` instead of
 * propagating. This is what keeps a typo in one optional LLM variable from crash-looping the
 * whole server at top-level `loadConfig()` — see src/index.ts.
 */
function withLlmFallback<T>(errors: string[], fallback: T, run: () => T): T {
  try {
    return run();
  } catch (e) {
    errors.push((e as Error).message);
    return fallback;
  }
}

export function loadConfig(): Config {
  const pluginApiToken = process.env.PLUGIN_API_TOKEN || "";
  const llmConfigErrors: string[] = [];

  const llmApiKey = withLlmFallback(llmConfigErrors, "", () =>
    headerSafeEnv("LLM_API_KEY", process.env.LLM_API_KEY || process.env.OPENROUTER_API_KEY || "")
  );
  const llmHttpReferer = withLlmFallback(llmConfigErrors, "", () =>
    headerSafeEnv("LLM_HTTP_REFERER", process.env.LLM_HTTP_REFERER || "")
  );
  const llmTitle = withLlmFallback(llmConfigErrors, "", () =>
    headerSafeEnv("LLM_TITLE", process.env.LLM_TITLE || "")
  );
  const llmExtraHeaders = withLlmFallback(llmConfigErrors, {} as Record<string, string>, () =>
    parseLlmExtraHeaders(process.env.LLM_EXTRA_HEADERS)
  );
  const llmRelayTokens = withLlmFallback(llmConfigErrors, [] as string[], () =>
    parseLlmRelayTokens(process.env.LLM_RELAY_TOKENS, pluginApiToken)
  );
  const llmRelayTimeoutSeconds = withLlmFallback(llmConfigErrors, DEFAULT_LLM_RELAY_TIMEOUT_SECONDS, () =>
    parseLlmRelayTimeoutSeconds(process.env.LLM_RELAY_TIMEOUT_SECONDS)
  );

  return {
    port: parseInt(process.env.PORT || "3000", 10),
    whatsappVerifyToken: process.env.WHATSAPP_VERIFY_TOKEN || "",
    whatsappAccessToken: process.env.WHATSAPP_ACCESS_TOKEN || "",
    whatsappPhoneNumberId: process.env.WHATSAPP_PHONE_NUMBER_ID || "",
    telegramBotToken: process.env.TELEGRAM_BOT_TOKEN || "",
    pluginApiToken,
    databasePath: process.env.DATABASE_PATH || "./data/hub.sqlite",
    llmApiKey,
    llmEndpoint: process.env.LLM_ENDPOINT || "https://openrouter.ai/api/v1",
    agentModel: process.env.AGENT_MODEL || "anthropic/claude-sonnet-4",
    agentRequestTimeout: parseInt(process.env.AGENT_REQUEST_TIMEOUT || "30000", 10),
    sessionMessageLimit: parseInt(process.env.SESSION_MESSAGE_LIMIT || "50", 10),
    eventRetentionDays: parseInt(process.env.EVENT_RETENTION_DAYS || "30", 10),
    webhookSecret: process.env.WEBHOOK_SECRET || "",
    httpAllowlist: parseHttpAllowlist(process.env.HTTP_ALLOWLIST),
    listLimitMax: Math.max(1, parseInt(process.env.LIST_LIMIT_MAX || "100", 10)),
    llmHttpReferer,
    llmTitle,
    llmExtraHeaders,
    llmRelayTokens,
    llmAllowedModels: parseLlmAllowedModels(process.env.LLM_ALLOWED_MODELS),
    llmRelayTimeoutSeconds,
    llmConfigErrors,
    baseUrl: resolveBaseUrl(),
  };
}

export function validateConfig(config: Config): string[] {
  const errors: string[] = [];
  if (!config.pluginApiToken) {
    errors.push("PLUGIN_API_TOKEN is required");
  }
  return errors;
}

export function validateAgentConfig(config: Config): string[] {
  const warnings: string[] = [...config.llmConfigErrors];
  if (!hasLlmCredentials(config)) {
    warnings.push("LLM_API_KEY not set - agent chat endpoints will return 503");
  }
  return warnings;
}
