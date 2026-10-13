// LLM gateway relay — see routes/AGENTS.md §llm-relay
import type { Config } from "../../config";
import { hasLlmCredentials } from "../../config";
import { authenticateRelay, unauthorizedResponse } from "../../middleware/auth";
import { buildUpstreamHeaders, redactCredentials } from "../../services/llm";

/** Relay traffic since boot, reported on /health (counts and statuses only, never content). */
export const relayStats = { requests: 0, lastStatus: null as number | null };

/** Hard cap on the client request body; a runaway/hostile client can't make the relay buffer more. */
const LLM_RELAY_MAX_BODY_BYTES = 4 * 1024 * 1024;

/**
 * Fields forwarded upstream. Everything else — `stream` (would need buffering), `n` (uncapped
 * fan-out), `api_base`/`base_url`/`api_key` (LiteLLM honours these in the body, CVE-2024-6587
 * class — a client could redirect the server's own credentials elsewhere), `models`/`provider`
 * (OpenRouter routing overrides) — is dropped silently, never forwarded.
 */
const ALLOWED_BODY_FIELDS = [
  "model",
  "messages",
  "tools",
  "tool_choice",
  "parallel_tool_calls",
  "temperature",
  "top_p",
  "max_tokens",
  "max_completion_tokens",
  "stop",
  "response_format",
  "seed",
  "presence_penalty",
  "frequency_penalty",
  "reasoning",
] as const;

function pickAllowedBodyFields(body: Record<string, unknown>): Record<string, unknown> {
  const picked: Record<string, unknown> = {};
  for (const field of ALLOWED_BODY_FIELDS) {
    if (field in body) picked[field] = body[field];
  }
  return picked;
}

/** OpenAI-shaped error envelope — the plugin's response parser reads error.message. */
function openAiError(message: string, type: string, status: number): Response {
  return Response.json({ error: { message, type } }, { status });
}

function payloadTooLargeError(): Response {
  return openAiError(
    `Request body exceeds the maximum allowed size of ${LLM_RELAY_MAX_BODY_BYTES} bytes`,
    "payload_too_large",
    413
  );
}

function notConfiguredError(): Response {
  return openAiError(
    "The AI Hub server has no LLM credentials configured. Set LLM_API_KEY (or LLM_EXTRA_HEADERS) on the server.",
    "relay_not_configured",
    503
  );
}

/** Checked before notConfiguredError: a broken LLM env var disables LLM, distinct from never having set one up. */
function misconfiguredError(config: Config): Response {
  return openAiError(
    `LLM is disabled on this server: ${config.llmConfigErrors.join("; ")}`,
    "relay_misconfigured",
    503
  );
}

type RelayOptions = { path: string; method: "GET" | "POST"; body?: Record<string, unknown> };

/** Forwards to the gateway and records the outcome in relayStats. */
async function relay(config: Config, opts: RelayOptions): Promise<Response> {
  relayStats.requests += 1;
  const response = await forward(config, opts);
  relayStats.lastStatus = response.status;
  return response;
}

async function forward(config: Config, opts: RelayOptions): Promise<Response> {
  const base = config.llmEndpoint.replace(/\/+$/, "");
  const url = `${base}${opts.path}`;

  try {
    const response = await fetch(url, {
      method: opts.method,
      headers: buildUpstreamHeaders(config, { json: opts.method === "POST" }),
      body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
      redirect: "error", // a cross-origin redirect drops Authorization but can keep e.g. api-key
      signal: AbortSignal.timeout(config.llmRelayTimeoutSeconds * 1000),
    });

    const text = await response.text();
    // A gateway that echoes a bad credential in its error body must not hand it to plugin users.
    return new Response(redactCredentials(text, config), {
      status: response.status,
      headers: { "Content-Type": response.headers.get("Content-Type") || "application/json" },
    });
  } catch (error: any) {
    if (error?.name === "TimeoutError") {
      return openAiError(
        `The LLM gateway did not answer within ${config.llmRelayTimeoutSeconds}s`,
        "relay_timeout",
        504
      );
    }
    let origin = base;
    try {
      origin = new URL(base).origin;
    } catch {
      /* base isn't a valid absolute URL — report it as-is */
    }
    // Plugin users get only the error's code/name; the redacted detail stays in server logs.
    console.error(`[llm-relay] ${origin}: ${redactCredentials(String(error?.message ?? error), config)}`);
    return openAiError(
      `Could not reach the LLM gateway at ${origin} (${error?.code || error?.name || "network error"}). See the AI Hub server logs.`,
      "relay_unreachable",
      502
    );
  }
}

export async function handleLlmChatCompletions(req: Request, config: Config): Promise<Response> {
  if (!authenticateRelay(req, config)) return unauthorizedResponse();
  if (config.llmConfigErrors.length > 0) return misconfiguredError(config);
  if (!hasLlmCredentials(config)) return notConfiguredError();

  const contentLength = req.headers.get("Content-Length");
  if (contentLength && Number(contentLength) > LLM_RELAY_MAX_BODY_BYTES) {
    return payloadTooLargeError();
  }

  let rawBody: string;
  try {
    rawBody = await req.text();
  } catch {
    return openAiError("Request body must be a JSON object", "invalid_request", 400);
  }
  if (Buffer.byteLength(rawBody, "utf8") > LLM_RELAY_MAX_BODY_BYTES) {
    return payloadTooLargeError();
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(rawBody);
  } catch {
    return openAiError("Request body must be a JSON object", "invalid_request", 400);
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return openAiError("Request body must be a JSON object", "invalid_request", 400);
  }

  const body = pickAllowedBodyFields(parsed as Record<string, unknown>);
  if (typeof body.model !== "string" || !body.model.trim()) {
    body.model = config.agentModel;
  }

  const model = body.model as string;
  if (config.llmAllowedModels.length > 0 && !config.llmAllowedModels.includes(model)) {
    return openAiError(
      `Model "${model}" is not allowed by this server. Allowed: ${config.llmAllowedModels.join(", ")}`,
      "model_not_allowed",
      400
    );
  }

  return relay(config, { path: "/chat/completions", method: "POST", body });
}

export async function handleLlmModels(req: Request, config: Config): Promise<Response> {
  if (!authenticateRelay(req, config)) return unauthorizedResponse();
  if (config.llmConfigErrors.length > 0) return misconfiguredError(config);
  if (!hasLlmCredentials(config)) return notConfiguredError();

  return relay(config, { path: "/models", method: "GET" });
}
