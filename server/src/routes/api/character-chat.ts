import type { Database } from "bun:sqlite";
import type { Config } from "../../config";
import type { AgentBridge } from "../../services/agent-bridge";
import type { ConversationStore } from "../../services/conversations";
import { authenticate, unauthorizedResponse } from "../../middleware/auth";
import { errorResponse, notFoundResponse } from "../../helpers/responses";
import { getCharacter, getCharacterByName } from "../../db/characters";
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

export async function handleCharacterChat(
  req: Request,
  config: Config,
  db: Database,
  bridge: AgentBridge | undefined,
  conversations: ConversationStore,
  params: Record<string, string>,
  traceId?: string
): Promise<Response> {
  if (!authenticate(req, config)) return unauthorizedResponse();

  if (!config.llmApiKey) {
    return errorResponse(503, "LLM API key not configured");
  }

  const character = resolveCharacter(db, params.id);
  if (!character) return notFoundResponse("Character not found");

  let body: { message?: string; conversationId?: string };
  try {
    body = await req.json();
  } catch {
    return errorResponse(400, "Invalid JSON body");
  }

  if (!body.message || typeof body.message !== "string" || !body.message.trim()) {
    return errorResponse(400, "Missing required field: message");
  }

  let conv = body.conversationId ? conversations.get(body.conversationId) : null;

  if (!conv) {
    conv = conversations.create();

    const memories =
      bridge?.isPluginConnected()
        ? await fetchMemories(bridge, character.memory_tag, traceId)
        : "";

    conversations.addMessage(conv.id, {
      role: "system",
      content: buildSystemContent(character.name, character.system_prompt, memories),
    });
  }

  conversations.addMessage(conv.id, { role: "user", content: body.message.trim() });

  try {
    const llmResponse = await chatCompletion(
      conv.messages,
      undefined,
      config,
      character.model ?? undefined
    );

    const response = llmResponse.content ?? "...";
    conversations.addMessage(conv.id, { role: "assistant", content: response });

    return Response.json({
      success: true,
      data: {
        conversationId: conv.id,
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
