import { Database } from "bun:sqlite";
import type { Message, MessageWithContact } from "../types";

export function insertMessage(
  db: Database,
  msg: {
    externalId?: string | null;
    contactId: string;
    platform: string;
    direction: string;
    content: string;
    mediaType?: string | null;
    mediaUrl?: string | null;
    status?: string;
    rawPayload?: string | null;
  }
): Message | null {
  const result = db.run(
    `INSERT OR IGNORE INTO messages (external_id, contact_id, platform, direction, content, media_type, media_url, status, raw_payload)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      msg.externalId || null,
      msg.contactId,
      msg.platform,
      msg.direction,
      msg.content,
      msg.mediaType || null,
      msg.mediaUrl || null,
      msg.status || "received",
      msg.rawPayload || null,
    ]
  );

  if (result.changes === 0) return null; // Duplicate

  return getMessage(db, Number(result.lastInsertRowid));
}

export function getMessage(db: Database, id: number): Message | null {
  return db.query(
    `SELECT id, external_id as externalId, contact_id as contactId, platform,
            direction, content, media_type as mediaType, media_url as mediaUrl,
            status, raw_payload as rawPayload, created_at as createdAt
     FROM messages WHERE id = ?`
  ).get(id) as Message | null;
}

export function getMessages(
  db: Database,
  options: {
    contactId?: string;
    limit?: number;
    offset?: number;
    since?: string;
  } = {}
): { messages: MessageWithContact[]; total: number; hasMore: boolean } {
  const { contactId, limit = 50, offset = 0, since } = options;

  const conditions: string[] = [];
  const params: (string | number)[] = [];

  if (contactId) {
    conditions.push("m.contact_id = ?");
    params.push(contactId);
  }
  if (since) {
    conditions.push("m.created_at > ?");
    params.push(since);
  }

  const whereClause = conditions.length > 0 ? `WHERE ${conditions.join(" AND ")}` : "";

  const countResult = db.query(
    `SELECT COUNT(*) as total FROM messages m ${whereClause}`
  ).get(...params) as { total: number };

  const total = countResult.total;

  const messages = db.query(
    `SELECT m.id, m.external_id as externalId, m.contact_id as contactId, m.platform,
            m.direction, m.content, m.media_type as mediaType, m.media_url as mediaUrl,
            m.status, m.raw_payload as rawPayload, m.created_at as createdAt,
            c.id as "contact.id", c.platform as "contact.platform",
            c.platform_user_id as "contact.platformUserId",
            c.display_name as "contact.displayName",
            c.metadata as "contact.metadata",
            c.created_at as "contact.createdAt",
            c.updated_at as "contact.updatedAt"
     FROM messages m
     JOIN contacts c ON m.contact_id = c.id
     ${whereClause}
     ORDER BY m.created_at DESC
     LIMIT ? OFFSET ?`
  ).all(...params, limit, offset) as any[];

  const formattedMessages: MessageWithContact[] = messages.map((row) => ({
    id: row.id,
    externalId: row.externalId,
    contactId: row.contactId,
    platform: row.platform,
    direction: row.direction,
    content: row.content,
    mediaType: row.mediaType,
    mediaUrl: row.mediaUrl,
    status: row.status,
    rawPayload: row.rawPayload,
    createdAt: row.createdAt,
    contact: {
      id: row["contact.id"],
      platform: row["contact.platform"],
      platformUserId: row["contact.platformUserId"],
      displayName: row["contact.displayName"],
      metadata: row["contact.metadata"],
      createdAt: row["contact.createdAt"],
      updatedAt: row["contact.updatedAt"],
    },
  }));

  return {
    messages: formattedMessages,
    total,
    hasMore: offset + limit < total,
  };
}
