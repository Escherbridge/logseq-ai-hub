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

  const response = await fetch(endpoint, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Authorization": `Bearer ${config.llmApiKey}`,
      ...(config.llmHttpReferer ? { "HTTP-Referer": config.llmHttpReferer } : {}),
      ...(config.llmTitle ? { "X-Title": config.llmTitle } : {}),
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(30000),
  });

  if (!response.ok) {
    const errorText = await response.text().catch(() => "Unknown error");
    throw new Error(`LLM API error (${response.status}): ${errorText}`);
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
