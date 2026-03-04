import { Database } from "bun:sqlite";

export function initializeSchema(db: Database): void {
  db.exec(`
    CREATE TABLE IF NOT EXISTS contacts (
      id TEXT PRIMARY KEY,
      platform TEXT NOT NULL,
      platform_user_id TEXT NOT NULL,
      display_name TEXT,
      metadata TEXT,
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      updated_at TEXT NOT NULL DEFAULT (datetime('now'))
    );

    CREATE INDEX IF NOT EXISTS idx_contacts_platform ON contacts(platform);

    CREATE TABLE IF NOT EXISTS messages (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      external_id TEXT,
      contact_id TEXT NOT NULL,
      platform TEXT NOT NULL,
      direction TEXT NOT NULL,
      content TEXT NOT NULL,
      media_type TEXT,
      media_url TEXT,
      status TEXT NOT NULL DEFAULT 'received',
      raw_payload TEXT,
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      FOREIGN KEY (contact_id) REFERENCES contacts(id)
    );

    CREATE INDEX IF NOT EXISTS idx_messages_contact ON messages(contact_id);
    CREATE INDEX IF NOT EXISTS idx_messages_platform ON messages(platform);
    CREATE INDEX IF NOT EXISTS idx_messages_created ON messages(created_at);
    CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_external ON messages(external_id) WHERE external_id IS NOT NULL;

    CREATE TABLE IF NOT EXISTS sse_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      event_type TEXT NOT NULL,
      payload TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT (datetime('now'))
    );

    CREATE TABLE IF NOT EXISTS characters (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      persona TEXT,
      system_prompt TEXT,
      model TEXT,
      skills TEXT NOT NULL DEFAULT '[]',
      memory_tag TEXT NOT NULL,
      metadata TEXT NOT NULL DEFAULT '{}',
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      updated_at TEXT NOT NULL DEFAULT (datetime('now'))
    );

    CREATE UNIQUE INDEX IF NOT EXISTS idx_characters_name ON characters(name);

    CREATE TABLE IF NOT EXISTS character_sessions (
      id TEXT PRIMARY KEY,
      character_id TEXT NOT NULL,
      messages TEXT NOT NULL DEFAULT '[]',
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      updated_at TEXT NOT NULL DEFAULT (datetime('now')),
      FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS idx_character_sessions_character ON character_sessions(character_id);
    CREATE INDEX IF NOT EXISTS idx_character_sessions_updated ON character_sessions(updated_at);

    CREATE TABLE IF NOT EXISTS hub_events (
      id TEXT PRIMARY KEY,
      event_type TEXT NOT NULL,
      payload TEXT NOT NULL DEFAULT '{}',
      character_id TEXT,
      source TEXT,
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE SET NULL
    );

    CREATE INDEX IF NOT EXISTS idx_hub_events_type ON hub_events(event_type);
    CREATE INDEX IF NOT EXISTS idx_hub_events_character ON hub_events(character_id);
    CREATE INDEX IF NOT EXISTS idx_hub_events_created ON hub_events(created_at);
  `);
}
