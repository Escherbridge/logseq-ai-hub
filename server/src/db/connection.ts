import { Database } from "bun:sqlite";
import { initializeSchema } from "./schema";
import { mkdirSync, existsSync } from "fs";
import { dirname } from "path";

let db: Database | null = null;

export function getDatabase(path?: string): Database {
  if (db) return db;

  const dbPath = path || process.env.DATABASE_PATH || "./data/hub.sqlite";

  if (dbPath !== ":memory:") {
    const dir = dirname(dbPath);
    if (!existsSync(dir)) {
      mkdirSync(dir, { recursive: true });
    }
  }

  db = new Database(dbPath);
  db.exec("PRAGMA journal_mode = WAL");
  db.exec("PRAGMA foreign_keys = ON");
  initializeSchema(db);
  return db;
}

export function closeDatabase(): void {
  if (db) {
    db.close();
    db = null;
  }
}

export function createTestDatabase(): Database {
  const testDb = new Database(":memory:");
  testDb.exec("PRAGMA foreign_keys = ON");
  initializeSchema(testDb);
  return testDb;
}
