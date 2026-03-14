import { Database } from "bun:sqlite";

export interface CharacterRelationship {
  from_id: string;
  to_id: string;
  type: string;
  strength: number;
  notes: string | null;
  updated_at: string;
}

export function setRelationship(
  db: Database,
  fromId: string,
  toId: string,
  data: { type: string; strength?: number; notes?: string | null }
): CharacterRelationship {
  const strength = data.strength ?? 0;
  const notes = data.notes ?? null;
  const query = db.query(
    `INSERT INTO character_relationships (from_id, to_id, type, strength, notes, updated_at)
     VALUES (?, ?, ?, ?, ?, datetime('now'))
     ON CONFLICT(from_id, to_id) DO UPDATE SET
       type = excluded.type,
       strength = excluded.strength,
       notes = excluded.notes,
       updated_at = datetime('now')
     RETURNING *`
  );
  return query.get(fromId, toId, data.type, strength, notes) as CharacterRelationship;
}

export function getRelationship(
  db: Database,
  fromId: string,
  toId: string
): CharacterRelationship | null {
  return (
    (db
      .query(
        `SELECT * FROM character_relationships WHERE from_id = ? AND to_id = ?`
      )
      .get(fromId, toId) as CharacterRelationship | null) ?? null
  );
}

export function listRelationships(
  db: Database,
  characterId: string
): (CharacterRelationship & { direction: "outgoing" | "incoming" })[] {
  const outgoing = db
    .query(`SELECT *, 'outgoing' as direction FROM character_relationships WHERE from_id = ?`)
    .all(characterId) as (CharacterRelationship & { direction: "outgoing" })[];

  const incoming = db
    .query(`SELECT *, 'incoming' as direction FROM character_relationships WHERE to_id = ?`)
    .all(characterId) as (CharacterRelationship & { direction: "incoming" })[];

  return [...outgoing, ...incoming];
}

export function deleteRelationship(
  db: Database,
  fromId: string,
  toId: string
): boolean {
  const result = db.run(
    `DELETE FROM character_relationships WHERE from_id = ? AND to_id = ?`,
    [fromId, toId]
  );
  return result.changes > 0;
}
