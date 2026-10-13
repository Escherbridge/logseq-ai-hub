import type { Config } from "../config";

/**
 * Message shape accepted by chatCompletion.
 * Compatible with both the old ConversationMessage and the new SessionMessage mapping.
 */
export interface ChatMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
  toolCallId?: string;
  toolCalls?: any[];
}

export interface LLMResponse {
  content: string | null;
  toolCalls?: ToolCall[];
}

export interface ToolCall {
  id: string;
  function: {
    name: string;
    arguments: string;
  };
}

/**
 * Builds upstream LLM request headers: Content-Type (POST only), Bearer auth (when a key is
 * set), the OpenRouter leaderboard headers, then config.llmExtraHeaders merged last — an extra
 * header replaces any earlier header whose name matches case-insensitively (Azure's `api-key`
 * just adds; a custom `authorization` replaces the Bearer). Never includes client-supplied headers.
 */
export function buildUpstreamHeaders(config: Config, opts: { json: boolean }): Record<string, string> {
  const headers: Record<string, string> = {};
  if (opts.json) headers["Content-Type"] = "application/json";
  if (config.llmApiKey) headers["Authorization"] = `Bearer ${config.llmApiKey}`;
  if (config.llmHttpReferer) headers["HTTP-Referer"] = config.llmHttpReferer;
  if (config.llmTitle) headers["X-Title"] = config.llmTitle;

  for (const [name, value] of Object.entries(config.llmExtraHeaders)) {
    const lower = name.toLowerCase();
    const existingKey = Object.keys(headers).find((k) => k.toLowerCase() === lower);
    if (existingKey) delete headers[existingKey];
    headers[name] = value;
  }

  return headers;
}

/**
 * Replaces every configured credential in `text` with `[redacted]`, longest-first so a
 * secret that's a substring of another (e.g. a short token prefix of a longer one) doesn't
 * leave a readable remainder. Skips values under 4 chars to avoid mass-redacting incidental
 * substrings. Covers the LLM key/extra-headers, the plugin token and every relay token —
 * fetch errors, upstream error bodies and log lines can all quote one of these.
 */
export function redactCredentials(text: string, config: Config): string {
  const secrets = [
    config.llmApiKey,
    ...Object.values(config.llmExtraHeaders),
    config.pluginApiToken,
    ...config.llmRelayTokens,
  ]
    .filter((s) => s.length >= 4)
    .sort((a, b) => b.length - a.length);
  return secrets.reduce((acc, secret) => acc.split(secret).join("[redacted]"), text);
}

export async function chatCompletion(
  messages: ChatMessage[],
  tools: any[] | undefined,
  config: Config,
  modelOverride?: string
): Promise<LLMResponse> {
  const endpoint = `${config.llmEndpoint}/chat/completions`;

  const body: Record<string, unknown> = {
    model: modelOverride ?? config.agentModel,
    messages: messages.map(m => {
      const msg: Record<string, unknown> = { role: m.role, content: m.content };
      if (m.toolCallId) msg.tool_call_id = m.toolCallId;
      if (m.toolCalls) msg.tool_calls = m.toolCalls;
      return msg;
    }),
  };

  if (tools && tools.length > 0) {
    body.tools = tools;
  }

  let response: Response;
  try {
    response = await fetch(endpoint, {
      method: "POST",
      headers: buildUpstreamHeaders(config, { json: true }),
      body: JSON.stringify(body),
      redirect: "error",
      signal: AbortSignal.timeout(30000),
    });
  } catch (error: any) {
    // Bun quotes an invalid header value in error.message; redact before it reaches a caller/log.
    throw new Error(redactCredentials(String(error?.message ?? error), config));
  }

  if (!response.ok) {
    const errorText = await response.text().catch(() => "Unknown error");
    throw new Error(`LLM API error (${response.status}): ${redactCredentials(errorText, config)}`);
  }

  const data = await response.json();
  const choice = data.choices?.[0];

  if (!choice) {
    throw new Error("No choices in LLM response");
  }

  return {
    content: choice.message?.content ?? null,
    toolCalls: choice.message?.tool_calls,
  };
}
