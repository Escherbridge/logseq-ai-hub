export interface ConversationMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
  toolCallId?: string;
  toolCalls?: any[];
}

export interface Conversation {
  id: string;
  messages: ConversationMessage[];
  createdAt: number;
}

export class ConversationStore {
  private conversations: Map<string, Conversation> = new Map();
  private maxMessages = 20;

  create(): Conversation {
    const id = crypto.randomUUID();
    const conv: Conversation = { id, messages: [], createdAt: Date.now() };
    this.conversations.set(id, conv);
    return conv;
  }

  get(id: string): Conversation | null {
    return this.conversations.get(id) ?? null;
  }

  addMessage(id: string, message: ConversationMessage): void {
    const conv = this.conversations.get(id);
    if (!conv) return;
    conv.messages.push(message);
    // Cap at maxMessages, preserving system prompt
    if (conv.messages.length > this.maxMessages) {
      const systemMsgs = conv.messages.filter(m => m.role === "system");
      const nonSystem = conv.messages.filter(m => m.role !== "system");
      conv.messages = [...systemMsgs, ...nonSystem.slice(-(this.maxMessages - systemMsgs.length))];
    }
  }

  delete(id: string): void {
    this.conversations.delete(id);
  }
}
