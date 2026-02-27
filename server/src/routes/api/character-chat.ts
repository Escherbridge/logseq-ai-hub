import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import type { ConversationMessage } from "../../services/conversations";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { errorResponse, notFoundResponse } from "../../helpers/responses";
import { getCharacter, getCharacterByName, updateCharacter } from "../../db/characters";
import type { Character } from "../../db/characters";
import {
  createCharacterSession,
  getCharacterSession,
  saveCharacterSession,
} from "../../db/character-sessions";
import { chatCompletion } from "../../services/llm";

function resolveCharacter(db: Database, idOrName: string) {
  return getCharacter(db, idOrName) ?? getCharacterByName(db, idOrName);
}

async function fetchMemories(bridge: AgentBridge, tag: string, traceId?: string): Promise<string> {
  try {
    const result = await bridge.sendRequest("recall_memory", { tag }, traceId) as any;
    const entries: string[] = Array.isArray(result?.memories)
      ? result.memories.map((m: any) => String(m.content ?? m))
      : Array.isArray(result)
        ? result.map((m: any) => String(m.content ?? m))
        : [];
    return entries.length > 0 ? entries.join("\n") : "";
  } catch {
    return "";
  }
}

function buildSystemContent(name: string, systemPrompt: string | null, memories: string): string {
  const base = systemPrompt?.trim() || `You are ${name}. Stay in character at all times.`;
  if (!memories) return base;
  return `${base}\n\n## Your memories\n${memories}`;
}

function buildCharacterTools(pluginConnected: boolean): any[] {
  const tools: any[] = [
    {
      type: "function",
      function: {
        name: "update_state",
        description: "Update your character state — hp, mood, quest progress, inventory, relationships, or any other attribute. Call this whenever something meaningful changes.",
        parameters: {
          type: "object",
          properties: {
            updates: {
              type: "object",
              description: "Key-value pairs of state fields to update",
              additionalProperties: true,
            },
          },
          required: ["updates"],
        },
      },
    },
  ];

  if (pluginConnected) {
    tools.push({
      type: "function",
      function: {
        name: "store_memory",
        description: "Permanently store something you want to remember across conversations. Use for important facts, events, or relationships.",
        parameters: {
          type: "object",
          properties: {
            content: { type: "string", description: "What to remember" },
          },
          required: ["content"],
        },
      },
    });

    tools.push({
      type: "function",
      function: {
        name: "recall_memory",
        description: "Retrieve your stored memories. Use when you need to remember something from a past conversation.",
        parameters: { type: "object", properties: {} },
      },
    });
  }

  return tools;
}

async function executeCharacterTool(
  toolName: string,
  args: Record<string, unknown>,
  character: Character,
  db: Database,
  bridge: AgentBridge | undefined,
  traceId?: string
): Promise<{ success: boolean; result: unknown }> {
  if (toolName === "update_state") {
    const updates = (args.updates ?? {}) as Record<string, unknown>;
    const merged = { ...character.metadata, ...updates };
    updateCharacter(db, character.id, { metadata: merged });
    Object.assign(character.metadata, updates);
    return { success: true, result: { updated: Object.keys(updates) } };
  }

  if (toolName === "store_memory") {
    if (!bridge?.isPluginConnected()) {
      return { success: false, result: { error: "Logseq plugin not connected" } };
    }
    try {
      const result = await bridge.sendRequest(
        "store_memory",
        { tag: character.memory_tag, content: String(args.content ?? "") },
        traceId
      );
      return { success: true, result };
    } catch (err: any) {
      return { success: false, result: { error: err.message } };
    }
  }

  if (toolName === "recall_memory") {
    if (!bridge?.isPluginConnected()) {
      return { success: false, result: { error: "Logseq plugin not connected" } };
    }
    try {
      const result = await bridge.sendRequest("recall_memory", { tag: character.memory_tag }, traceId);
      return { success: true, result };
    } catch (err: any) {
      return { success: false, result: { error: err.message } };
    }
  }

  return { success: false, result: { error: `Unknown tool: ${toolName}` } };
}

export async function handleCharacterChat(
  req: Request,
  config: Config,
  db: Database,
  bridge: AgentBridge | undefined,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  if (!config.llmApiKey) {
    return errorResponse(503, "LLM API key not configured");
  }

  const character = resolveCharacter(db, params.id);
  if (!character) return notFoundResponse("Character not found");

  let body: { message?: string; sessionId?: string };
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  if (!body.message || typeof body.message !== "string" || !body.message.trim()) {
    return errorResponse(400, "Missing required field: message");
  }

  const pluginConnected = bridge?.isPluginConnected() ?? false;

  let session = body.sessionId ? getCharacterSession(db, body.sessionId) : null;

  if (!session) {
    const memories = pluginConnected
      ? await fetchMemories(bridge!, character.memory_tag, traceId)
      : "";

    const systemMessage: ConversationMessage = {
      role: "system",
      content: buildSystemContent(character.name, character.system_prompt, memories),
    };

    session = createCharacterSession(db, character.id, [systemMessage]);
  }

  const messages = [...session.messages];
  messages.push({ role: "user", content: body.message.trim() });

  const tools = buildCharacterTools(pluginConnected);

  try {
    let llmResponse = await chatCompletion(messages, tools, config, character.model ?? undefined);

    let rounds = 0;
    while (llmResponse.toolCalls && llmResponse.toolCalls.length > 0 && rounds < 3) {
      rounds++;

      messages.push({
        role: "assistant",
        content: llmResponse.content ?? "",
        toolCalls: llmResponse.toolCalls,
      });

      for (const toolCall of llmResponse.toolCalls) {
        let args: Record<string, unknown> = {};
        try { args = JSON.parse(toolCall.function.arguments); } catch { /* empty */ }

        const { result } = await executeCharacterTool(
          toolCall.function.name,
          args,
          character,
          db,
          bridge,
          traceId
        );

        messages.push({
          role: "tool",
          content: JSON.stringify(result),
          toolCallId: toolCall.id,
        });
      }

      llmResponse = await chatCompletion(messages, tools, config, character.model ?? undefined);
    }

    const response = llmResponse.content ?? "...";
    messages.push({ role: "assistant", content: response });

    saveCharacterSession(db, session.id, messages);

    return Response.json({
      success: true,
      data: {
        sessionId: session.id,
        character: { id: character.id, name: character.name },
        response,
      },
    });
  } catch (err: any) {
    if (err.name === "AbortError" || err.message?.includes("timeout")) {
      return errorResponse(504, "LLM request timed out");
    }
    return errorResponse(500, `Character chat error: ${err.message}`);
  }
}
