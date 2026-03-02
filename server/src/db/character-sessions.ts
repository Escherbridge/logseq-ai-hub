import { Database } from "bun:sqlite";
import type { ConversationMessage } from "../services/conversations";

export interface CharacterSession {
  id: string;
  character_id: string;
  messages: ConversationMessage[];
  created_at: string;
  updated_at: string;
}

interface CharacterSessionRow {
  id: string;
  character_id: string;
  messages: string;
  created_at: string;
  updated_at: string;
}

function parseRow(row: CharacterSessionRow): CharacterSession {
  return { ...row, messages: JSON.parse(row.messages) };
}

export function createCharacterSession(
  db: Database,
  characterId: string,
  messages: ConversationMessage[] = []
): CharacterSession {
  const id = crypto.randomUUID();
  db.run(
    `INSERT INTO character_sessions (id, character_id, messages) VALUES (?, ?, ?)`,
    [id, characterId, JSON.stringify(messages)]
  );
  return getCharacterSession(db, id)!;
}

export function getCharacterSession(db: Database, id: string): CharacterSession | null {
  const row = db.query(`SELECT * FROM character_sessions WHERE id = ?`).get(id) as CharacterSessionRow | null;
  return row ? parseRow(row) : null;
}

export function listCharacterSessions(db: Database, characterId: string): CharacterSession[] {
  const rows = db.query(
    `SELECT * FROM character_sessions WHERE character_id = ? ORDER BY updated_at DESC`
  ).all(characterId) as CharacterSessionRow[];
  return rows.map(parseRow);
}

export function saveCharacterSession(
  db: Database,
  id: string,
  messages: ConversationMessage[]
): void {
  db.run(
    `UPDATE character_sessions SET messages = ?, updated_at = datetime('now') WHERE id = ?`,
    [JSON.stringify(messages), id]
  );
}

export function deleteCharacterSession(db: Database, id: string): boolean {
  const result = db.run(`DELETE FROM character_sessions WHERE id = ?`, [id]);
  return result.changes > 0;
}
