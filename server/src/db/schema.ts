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

    CREATE TABLE IF NOT EXISTS sessions (
      id TEXT PRIMARY KEY,
      name TEXT,
      agent_id TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'active',
      context TEXT NOT NULL DEFAULT '{}',
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      updated_at TEXT NOT NULL DEFAULT (datetime('now')),
      last_active_at TEXT NOT NULL DEFAULT (datetime('now'))
    );

    CREATE TABLE IF NOT EXISTS session_messages (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
      role TEXT NOT NULL,
      content TEXT NOT NULL,
      tool_calls TEXT,
      tool_call_id TEXT,
      metadata TEXT,
      created_at TEXT NOT NULL DEFAULT (datetime('now'))
    );

    CREATE INDEX IF NOT EXISTS idx_session_messages_session ON session_messages(session_id);
    CREATE INDEX IF NOT EXISTS idx_sessions_agent ON sessions(agent_id);
    CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(status);

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

    CREATE TABLE IF NOT EXISTS event_subscriptions (
      id TEXT PRIMARY KEY,
      event_type TEXT NOT NULL,
      character_id TEXT,
      job_skill TEXT NOT NULL,
      job_name_prefix TEXT NOT NULL,
      priority INTEGER NOT NULL DEFAULT 3,
      enabled INTEGER NOT NULL DEFAULT 1,
      created_at TEXT NOT NULL DEFAULT (datetime('now')),
      FOREIGN KEY (character_id) REFERENCES characters(id) ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS idx_event_subscriptions_type ON event_subscriptions(event_type);
    CREATE INDEX IF NOT EXISTS idx_event_subscriptions_character ON event_subscriptions(character_id);
    CREATE INDEX IF NOT EXISTS idx_event_subscriptions_enabled ON event_subscriptions(enabled);

    CREATE TABLE IF NOT EXISTS character_relationships (
      from_id TEXT NOT NULL,
      to_id TEXT NOT NULL,
      type TEXT NOT NULL,
      strength INTEGER NOT NULL DEFAULT 0,
      notes TEXT,
      updated_at TEXT NOT NULL DEFAULT (datetime('now')),
      PRIMARY KEY (from_id, to_id),
      FOREIGN KEY (from_id) REFERENCES characters(id) ON DELETE CASCADE,
      FOREIGN KEY (to_id) REFERENCES characters(id) ON DELETE CASCADE
    );

    CREATE INDEX IF NOT EXISTS idx_char_rel_from ON character_relationships(from_id);
    CREATE INDEX IF NOT EXISTS idx_char_rel_to ON character_relationships(to_id);

    CREATE TABLE IF NOT EXISTS events (
      id TEXT PRIMARY KEY,
      type TEXT NOT NULL,
      source TEXT NOT NULL,
      data TEXT NOT NULL,
      metadata TEXT,
      created_at TEXT NOT NULL DEFAULT (datetime('now'))
    );
    CREATE INDEX IF NOT EXISTS idx_events_type ON events(type);
    CREATE INDEX IF NOT EXISTS idx_events_source ON events(source);
    CREATE INDEX IF NOT EXISTS idx_events_created ON events(created_at);
  `);
}
