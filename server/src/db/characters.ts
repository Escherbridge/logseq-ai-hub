import { Database } from "bun:sqlite";

export interface Character {
  id: string;
  name: string;
  persona: string | null;
  system_prompt: string | null;
  model: string | null;
  skills: string[];
  memory_tag: string;
  metadata: Record<string, unknown>;
  created_at: string;
  updated_at: string;
}

interface CharacterRow {
  id: string;
  name: string;
  persona: string | null;
  system_prompt: string | null;
  model: string | null;
  skills: string;
  memory_tag: string;
  metadata: string;
  created_at: string;
  updated_at: string;
}

function parseRow(row: CharacterRow): Character {
  return {
    ...row,
    skills: JSON.parse(row.skills),
    metadata: JSON.parse(row.metadata),
  };
}

export function createCharacter(
  db: Database,
  data: {
    name: string;
    persona?: string;
    system_prompt?: string;
    model?: string;
    skills?: string[];
    metadata?: Record<string, unknown>;
  }
): Character {
  const id = crypto.randomUUID();
  const memoryTag = `character-${id.slice(0, 8)}`;

  db.run(
    `INSERT INTO characters (id, name, persona, system_prompt, model, skills, memory_tag, metadata)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      id,
      data.name,
      data.persona ?? null,
      data.system_prompt ?? null,
      data.model ?? null,
      JSON.stringify(data.skills ?? []),
      memoryTag,
      JSON.stringify(data.metadata ?? {}),
    ]
  );

  return getCharacter(db, id)!;
}

export function getCharacter(db: Database, id: string): Character | null {
  const row = db.query(`SELECT * FROM characters WHERE id = ?`).get(id) as CharacterRow | null;
  return row ? parseRow(row) : null;
}

export function getCharacterByName(db: Database, name: string): Character | null {
  const row = db.query(`SELECT * FROM characters WHERE name = ?`).get(name) as CharacterRow | null;
  return row ? parseRow(row) : null;
}

export function listCharacters(db: Database): Character[] {
  const rows = db.query(`SELECT * FROM characters ORDER BY name ASC`).all() as CharacterRow[];
  return rows.map(parseRow);
}

export function updateCharacter(
  db: Database,
  id: string,
  data: Partial<{
    name: string;
    persona: string | null;
    system_prompt: string | null;
    model: string | null;
    skills: string[];
    metadata: Record<string, unknown>;
  }>
): Character | null {
  const existing = getCharacter(db, id);
  if (!existing) return null;

  const sets: string[] = ["updated_at = datetime('now')"];
  const values: unknown[] = [];

  if (data.name !== undefined) { sets.push("name = ?"); values.push(data.name); }
  if (data.persona !== undefined) { sets.push("persona = ?"); values.push(data.persona); }
  if (data.system_prompt !== undefined) { sets.push("system_prompt = ?"); values.push(data.system_prompt); }
  if (data.model !== undefined) { sets.push("model = ?"); values.push(data.model); }
  if (data.skills !== undefined) { sets.push("skills = ?"); values.push(JSON.stringify(data.skills)); }
  if (data.metadata !== undefined) { sets.push("metadata = ?"); values.push(JSON.stringify(data.metadata)); }

  values.push(id);
  db.run(`UPDATE characters SET ${sets.join(", ")} WHERE id = ?`, values as string[]);

  return getCharacter(db, id);
}

export function deleteCharacter(db: Database, id: string): boolean {
  const result = db.run(`DELETE FROM characters WHERE id = ?`, [id]);
  return result.changes > 0;
}
