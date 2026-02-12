import { Database } from "bun:sqlite";
import type { Contact } from "../types";

export function upsertContact(
  db: Database,
  platform: string,
  platformUserId: string,
  displayName: string | null = null,
  metadata: string | null = null
): Contact {
  const id = `${platform}:${platformUserId}`;

  db.run(
    `INSERT INTO contacts (id, platform, platform_user_id, display_name, metadata)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT(id) DO UPDATE SET
       display_name = COALESCE(excluded.display_name, contacts.display_name),
       metadata = COALESCE(excluded.metadata, contacts.metadata),
       updated_at = datetime('now')`,
    [id, platform, platformUserId, displayName, metadata]
  );

  return getContact(db, id)!;
}

export function getContact(db: Database, id: string): Contact | null {
  const row = db.query(
    `SELECT id, platform, platform_user_id as platformUserId, display_name as displayName,
            metadata, created_at as createdAt, updated_at as updatedAt
     FROM contacts WHERE id = ?`
  ).get(id) as Contact | null;

  return row;
}

export function listContacts(db: Database, platform?: string): Contact[] {
  if (platform) {
    return db.query(
      `SELECT id, platform, platform_user_id as platformUserId, display_name as displayName,
              metadata, created_at as createdAt, updated_at as updatedAt
       FROM contacts WHERE platform = ? ORDER BY updated_at DESC`
    ).all(platform) as Contact[];
  }
  return db.query(
    `SELECT id, platform, platform_user_id as platformUserId, display_name as displayName,
            metadata, created_at as createdAt, updated_at as updatedAt
     FROM contacts ORDER BY updated_at DESC`
  ).all() as Contact[];
}
