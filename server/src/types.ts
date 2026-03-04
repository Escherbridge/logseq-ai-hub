export interface Contact {
  id: string;              // "platform:platform_user_id"
  platform: "whatsapp" | "telegram";
  platformUserId: string;
  displayName: string | null;
  metadata: string | null;  // JSON string
  createdAt: string;
  updatedAt: string;
}

export interface Message {
  id: number;
  externalId: string | null;
  contactId: string;
  platform: "whatsapp" | "telegram";
  direction: "incoming" | "outgoing";
  content: string;
  mediaType: string | null;
  mediaUrl: string | null;
  status: "received" | "sent" | "delivered" | "read" | "failed";
  rawPayload: string | null;
  createdAt: string;
}

export interface SSEEvent {
  type: "connected" | "new_message" | "message_sent" | "status_update" | "heartbeat" | "agent_request" | "agent_callback" | "job_created" | "job_started" | "job_completed" | "job_failed" | "job_cancelled" | "skill_created" | "approval_created" | "approval_resolved" | "approval_timeout" | "hub_event";
  data: Record<string, unknown>;
}

export interface MessageWithContact extends Message {
  contact: Contact;
}

export interface SendRequest {
  platform: "whatsapp" | "telegram";
  recipient: string;
  content: string;
}

export interface ApiResponse<T = unknown> {
  success: boolean;
  data?: T;
  error?: string;
}
