import type { Config } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import type { SessionStore } from "../../services/session-store";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { successResponse, errorResponse } from "../../helpers/responses";
import { buildTools, executeOperation } from "../../services/agent";
import {
  buildSessionSystemPrompt,
  resolveRelevantPages,
} from "../../services/session-context";
import { chatCompletion } from "../../services/llm";
import type { AgentAction } from "../../types/agent";
import type { Session } from "../../types/session";

/**
 * Message shape expected by chatCompletion (matches ConversationMessage).
 */
interface LLMMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
  toolCallId?: string;
  toolCalls?: any[];
}

export async function handleAgentChat(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  sessionStore: SessionStore,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  if (!config.llmApiKey) {
    return errorResponse(503, "LLM API key not configured");
  }

  let body: { message?: string; sessionId?: string; conversationId?: string };
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  if (!body.message || typeof body.message !== "string" || body.message.trim() === "") {
    return errorResponse(400, "Missing or empty required field: message");
  }

  // Backward compat: conversationId maps to sessionId
  const requestedSessionId = body.sessionId || body.conversationId;

  // Get or create session
  let session: Session | null = null;
  if (requestedSessionId) {
    session = sessionStore.get(requestedSessionId);
  }
  if (!session) {
    session = sessionStore.create({ agent_id: "agent-chat" });
  }

  const sessionId = session.id;

  // Load message history from SQLite
  const limit = config.sessionMessageLimit ?? 50;
  const historyMessages = sessionStore.getMessages(sessionId, { limit });

  // Resolve relevant pages for session context
  let pageContents: Map<string, string> | undefined;
  if (bridge && bridge.isPluginConnected() && session.context.relevant_pages?.length) {
    pageContents = await resolveRelevantPages(bridge, session.context.relevant_pages);
  }

  // Build enriched session system prompt (regenerated each time, not stored)
  const systemPrompt = buildSessionSystemPrompt(session, pageContents);

  // Store user message in session
  sessionStore.addMessage({
    session_id: sessionId,
    role: "user",
    content: body.message.trim(),
  });

  // Build LLM messages: system prompt + history + new user message
  const llmMessages: LLMMessage[] = [
    { role: "system", content: systemPrompt },
    ...historyMessages.map((m) => ({
      role: m.role,
      content: m.content,
      toolCallId: m.tool_call_id ?? undefined,
      toolCalls: m.tool_calls ?? undefined,
    })),
    { role: "user", content: body.message.trim() },
  ];

  const tools = bridge && bridge.isPluginConnected() ? buildTools() : undefined;
  const actions: AgentAction[] = [];

  try {
    // First LLM call
    let llmResponse = await chatCompletion(llmMessages, tools, config);

    // Handle tool calls (up to 5 rounds to prevent infinite loops)
    let rounds = 0;
    while (llmResponse.toolCalls && llmResponse.toolCalls.length > 0 && rounds < 5) {
      rounds++;

      // Store assistant message with tool calls
      sessionStore.addMessage({
        session_id: sessionId,
        role: "assistant",
        content: llmResponse.content || "",
        tool_calls: llmResponse.toolCalls,
      });

      // Also add to llmMessages for next LLM call
      llmMessages.push({
        role: "assistant",
        content: llmResponse.content || "",
        toolCalls: llmResponse.toolCalls,
      });

      // Execute each tool call
      for (const toolCall of llmResponse.toolCalls) {
        const fnName = toolCall.function.name;
        let fnArgs: Record<string, unknown> = {};
        try {
          fnArgs = JSON.parse(toolCall.function.arguments);
        } catch {
          fnArgs = {};
        }

        let result: { success: boolean; result: unknown; error?: string };
        if (bridge && bridge.isPluginConnected()) {
          result = await executeOperation(fnName, fnArgs, bridge, traceId);
        } else {
          result = { success: false, result: null, error: "Plugin not connected" };
        }

        actions.push({
          operation: fnName,
          params: fnArgs,
          result: result.result,
          success: result.success,
        });

        const toolContent = JSON.stringify(
          result.success ? result.result : { error: result.error }
        );

        // Store tool result in session
        sessionStore.addMessage({
          session_id: sessionId,
          role: "tool",
          content: toolContent,
          tool_call_id: toolCall.id,
        });

        // Also add to llmMessages for next LLM call
        llmMessages.push({
          role: "tool",
          content: toolContent,
          toolCallId: toolCall.id,
        });
      }

      // Call LLM again with tool results
      llmResponse = await chatCompletion(llmMessages, tools, config);
    }

    // Store final assistant response
    const responseText = llmResponse.content || "I completed the requested actions.";
    sessionStore.addMessage({
      session_id: sessionId,
      role: "assistant",
      content: responseText,
    });

    return successResponse({
      sessionId,
      conversationId: sessionId, // backward compat
      response: responseText,
      actions: actions.length > 0 ? actions : undefined,
    });
  } catch (err: any) {
    if (err.name === "AbortError" || err.message?.includes("timeout")) {
      return errorResponse(504, "LLM request timed out");
    }
    return errorResponse(500, `Agent error: ${err.message}`);
  }
}
