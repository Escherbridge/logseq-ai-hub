import { describe, it, expect, beforeEach } from "bun:test";
import { Database } from "bun:sqlite";
import { createTestDb } from "./helpers";

describe("Session Schema", () => {
  let db: Database;

  beforeEach(() => {
    db = createTestDb();
  });

  describe("sessions table", () => {
    it("should exist in sqlite_master", () => {
      const result = db.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='sessions'"
      ).get() as { name: string } | null;
      expect(result).not.toBeNull();
      expect(result!.name).toBe("sessions");
    });

    it("should have the correct columns", () => {
      const columns = db.query("PRAGMA table_info(sessions)").all() as Array<{
        name: string;
        type: string;
        notnull: number;
        dflt_value: string | null;
        pk: number;
      }>;

      const columnMap = new Map(columns.map((c) => [c.name, c]));

      // id: TEXT PRIMARY KEY
      expect(columnMap.get("id")).toBeDefined();
      expect(columnMap.get("id")!.type).toBe("TEXT");
      expect(columnMap.get("id")!.pk).toBe(1);

      // name: TEXT (nullable)
      expect(columnMap.get("name")).toBeDefined();
      expect(columnMap.get("name")!.type).toBe("TEXT");
      expect(columnMap.get("name")!.notnull).toBe(0);

      // agent_id: TEXT NOT NULL
      expect(columnMap.get("agent_id")).toBeDefined();
      expect(columnMap.get("agent_id")!.type).toBe("TEXT");
      expect(columnMap.get("agent_id")!.notnull).toBe(1);

      // status: TEXT NOT NULL DEFAULT 'active'
      expect(columnMap.get("status")).toBeDefined();
      expect(columnMap.get("status")!.type).toBe("TEXT");
      expect(columnMap.get("status")!.notnull).toBe(1);
      expect(columnMap.get("status")!.dflt_value).toBe("'active'");

      // context: TEXT NOT NULL DEFAULT '{}'
      expect(columnMap.get("context")).toBeDefined();
      expect(columnMap.get("context")!.type).toBe("TEXT");
      expect(columnMap.get("context")!.notnull).toBe(1);
      expect(columnMap.get("context")!.dflt_value).toBe("'{}'");

      // created_at: TEXT NOT NULL DEFAULT (datetime('now'))
      expect(columnMap.get("created_at")).toBeDefined();
      expect(columnMap.get("created_at")!.type).toBe("TEXT");
      expect(columnMap.get("created_at")!.notnull).toBe(1);
      expect(columnMap.get("created_at")!.dflt_value).toBe("datetime('now')");

      // updated_at: TEXT NOT NULL DEFAULT (datetime('now'))
      expect(columnMap.get("updated_at")).toBeDefined();
      expect(columnMap.get("updated_at")!.type).toBe("TEXT");
      expect(columnMap.get("updated_at")!.notnull).toBe(1);
      expect(columnMap.get("updated_at")!.dflt_value).toBe("datetime('now')");

      // last_active_at: TEXT NOT NULL DEFAULT (datetime('now'))
      expect(columnMap.get("last_active_at")).toBeDefined();
      expect(columnMap.get("last_active_at")!.type).toBe("TEXT");
      expect(columnMap.get("last_active_at")!.notnull).toBe(1);
      expect(columnMap.get("last_active_at")!.dflt_value).toBe("datetime('now')");
    });

    it("should have an index on agent_id", () => {
      const index = db.query(
        "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_sessions_agent'"
      ).get() as { name: string } | null;
      expect(index).not.toBeNull();
      expect(index!.name).toBe("idx_sessions_agent");
    });

    it("should have an index on status", () => {
      const index = db.query(
        "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_sessions_status'"
      ).get() as { name: string } | null;
      expect(index).not.toBeNull();
      expect(index!.name).toBe("idx_sessions_status");
    });
  });

  describe("session_messages table", () => {
    it("should exist in sqlite_master", () => {
      const result = db.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='session_messages'"
      ).get() as { name: string } | null;
      expect(result).not.toBeNull();
      expect(result!.name).toBe("session_messages");
    });

    it("should have the correct columns", () => {
      const columns = db.query("PRAGMA table_info(session_messages)").all() as Array<{
        name: string;
        type: string;
        notnull: number;
        dflt_value: string | null;
        pk: number;
      }>;

      const columnMap = new Map(columns.map((c) => [c.name, c]));

      // id: INTEGER PRIMARY KEY AUTOINCREMENT
      expect(columnMap.get("id")).toBeDefined();
      expect(columnMap.get("id")!.type).toBe("INTEGER");
      expect(columnMap.get("id")!.pk).toBe(1);

      // session_id: TEXT NOT NULL
      expect(columnMap.get("session_id")).toBeDefined();
      expect(columnMap.get("session_id")!.type).toBe("TEXT");
      expect(columnMap.get("session_id")!.notnull).toBe(1);

      // role: TEXT NOT NULL
      expect(columnMap.get("role")).toBeDefined();
      expect(columnMap.get("role")!.type).toBe("TEXT");
      expect(columnMap.get("role")!.notnull).toBe(1);

      // content: TEXT NOT NULL
      expect(columnMap.get("content")).toBeDefined();
      expect(columnMap.get("content")!.type).toBe("TEXT");
      expect(columnMap.get("content")!.notnull).toBe(1);

      // tool_calls: TEXT (nullable)
      expect(columnMap.get("tool_calls")).toBeDefined();
      expect(columnMap.get("tool_calls")!.type).toBe("TEXT");
      expect(columnMap.get("tool_calls")!.notnull).toBe(0);

      // tool_call_id: TEXT (nullable)
      expect(columnMap.get("tool_call_id")).toBeDefined();
      expect(columnMap.get("tool_call_id")!.type).toBe("TEXT");
      expect(columnMap.get("tool_call_id")!.notnull).toBe(0);

      // metadata: TEXT (nullable)
      expect(columnMap.get("metadata")).toBeDefined();
      expect(columnMap.get("metadata")!.type).toBe("TEXT");
      expect(columnMap.get("metadata")!.notnull).toBe(0);

      // created_at: TEXT NOT NULL DEFAULT (datetime('now'))
      expect(columnMap.get("created_at")).toBeDefined();
      expect(columnMap.get("created_at")!.type).toBe("TEXT");
      expect(columnMap.get("created_at")!.notnull).toBe(1);
      expect(columnMap.get("created_at")!.dflt_value).toBe("datetime('now')");
    });

    it("should have an index on session_id", () => {
      const index = db.query(
        "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_session_messages_session'"
      ).get() as { name: string } | null;
      expect(index).not.toBeNull();
      expect(index!.name).toBe("idx_session_messages_session");
    });
  });

  describe("foreign key constraints", () => {
    it("should enforce session_messages.session_id references sessions.id", () => {
      // Inserting a message with a non-existent session_id should fail
      expect(() => {
        db.run(
          `INSERT INTO session_messages (session_id, role, content) VALUES (?, ?, ?)`,
          ["nonexistent-session", "user", "Hello"]
        );
      }).toThrow();
    });

    it("should cascade delete session_messages when session is deleted", () => {
      // Insert a valid session
      db.run(
        `INSERT INTO sessions (id, agent_id) VALUES (?, ?)`,
        ["test-session-1", "claude-code"]
      );

      // Insert messages for this session
      db.run(
        `INSERT INTO session_messages (session_id, role, content) VALUES (?, ?, ?)`,
        ["test-session-1", "user", "Hello"]
      );
      db.run(
        `INSERT INTO session_messages (session_id, role, content) VALUES (?, ?, ?)`,
        ["test-session-1", "assistant", "Hi there"]
      );

      // Verify messages exist
      const beforeDelete = db.query(
        "SELECT COUNT(*) as count FROM session_messages WHERE session_id = ?"
      ).get("test-session-1") as { count: number };
      expect(beforeDelete.count).toBe(2);

      // Delete the session
      db.run("DELETE FROM sessions WHERE id = ?", ["test-session-1"]);

      // Messages should be cascaded
      const afterDelete = db.query(
        "SELECT COUNT(*) as count FROM session_messages WHERE session_id = ?"
      ).get("test-session-1") as { count: number };
      expect(afterDelete.count).toBe(0);
    });

    it("should allow inserting messages for existing sessions", () => {
      db.run(
        `INSERT INTO sessions (id, agent_id) VALUES (?, ?)`,
        ["test-session-2", "claude-code"]
      );

      expect(() => {
        db.run(
          `INSERT INTO session_messages (session_id, role, content) VALUES (?, ?, ?)`,
          ["test-session-2", "user", "This should work"]
        );
      }).not.toThrow();

      const msg = db.query(
        "SELECT * FROM session_messages WHERE session_id = ?"
      ).get("test-session-2") as { session_id: string; role: string; content: string };
      expect(msg.session_id).toBe("test-session-2");
      expect(msg.role).toBe("user");
      expect(msg.content).toBe("This should work");
    });
  });

  describe("default values", () => {
    it("should set default status to 'active' for new sessions", () => {
      db.run(
        `INSERT INTO sessions (id, agent_id) VALUES (?, ?)`,
        ["default-test-session", "claude-code"]
      );

      const session = db.query(
        "SELECT status FROM sessions WHERE id = ?"
      ).get("default-test-session") as { status: string };
      expect(session.status).toBe("active");
    });

    it("should set default context to '{}' for new sessions", () => {
      db.run(
        `INSERT INTO sessions (id, agent_id) VALUES (?, ?)`,
        ["context-test-session", "claude-code"]
      );

      const session = db.query(
        "SELECT context FROM sessions WHERE id = ?"
      ).get("context-test-session") as { context: string };
      expect(session.context).toBe("{}");
    });

    it("should auto-populate datetime fields for sessions", () => {
      db.run(
        `INSERT INTO sessions (id, agent_id) VALUES (?, ?)`,
        ["datetime-test-session", "claude-code"]
      );

      const session = db.query(
        "SELECT created_at, updated_at, last_active_at FROM sessions WHERE id = ?"
      ).get("datetime-test-session") as {
        created_at: string;
        updated_at: string;
        last_active_at: string;
      };
      expect(session.created_at).toBeTruthy();
      expect(session.updated_at).toBeTruthy();
      expect(session.last_active_at).toBeTruthy();
    });

    it("should auto-populate created_at for session messages", () => {
      db.run(
        `INSERT INTO sessions (id, agent_id) VALUES (?, ?)`,
        ["msg-datetime-session", "claude-code"]
      );
      db.run(
        `INSERT INTO session_messages (session_id, role, content) VALUES (?, ?, ?)`,
        ["msg-datetime-session", "user", "Test message"]
      );

      const msg = db.query(
        "SELECT created_at FROM session_messages WHERE session_id = ?"
      ).get("msg-datetime-session") as { created_at: string };
      expect(msg.created_at).toBeTruthy();
    });
  });
});
