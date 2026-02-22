import type { Config } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import type { ConversationStore } from "../../services/conversations";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { successResponse, errorResponse } from "../../helpers/responses";
import { buildSystemPrompt, buildTools, executeOperation } from "../../services/agent";
import { chatCompletion } from "../../services/llm";
import type { AgentAction } from "../../types/agent";

export async function handleAgentChat(
  req: Request,
  config: Config,
  bridge: AgentBridge | undefined,
  conversations: ConversationStore,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  if (!config.llmApiKey) {
    return errorResponse(503, "LLM API key not configured");
  }

  let body: { message?: string; conversationId?: string };
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  if (!body.message || typeof body.message !== "string" || body.message.trim() === "") {
    return errorResponse(400, "Missing or empty required field: message");
  }

  // Get or create conversation
  let conv = body.conversationId ? conversations.get(body.conversationId) : null;
  if (!conv) {
    conv = conversations.create();
    // Add system prompt as first message
    conversations.addMessage(conv.id, {
      role: "system",
      content: buildSystemPrompt(),
    });
  }

  // Add user message
  conversations.addMessage(conv.id, {
    role: "user",
    content: body.message.trim(),
  });

  const tools = bridge && bridge.isPluginConnected() ? buildTools() : undefined;
  const actions: AgentAction[] = [];

  try {
    // First LLM call
    let llmResponse = await chatCompletion(conv.messages, tools, config);

    // Handle tool calls (up to 5 rounds to prevent infinite loops)
    let rounds = 0;
    while (llmResponse.toolCalls && llmResponse.toolCalls.length > 0 && rounds < 5) {
      rounds++;

      // Store assistant message with tool calls
      conversations.addMessage(conv.id, {
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

        // Add tool result message
        conversations.addMessage(conv.id, {
          role: "tool",
          content: JSON.stringify(result.success ? result.result : { error: result.error }),
          toolCallId: toolCall.id,
        });
      }

      // Call LLM again with tool results
      llmResponse = await chatCompletion(conv.messages, tools, config);
    }

    // Store final assistant response
    const responseText = llmResponse.content || "I completed the requested actions.";
    conversations.addMessage(conv.id, {
      role: "assistant",
      content: responseText,
    });

    return successResponse({
      conversationId: conv.id,
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
