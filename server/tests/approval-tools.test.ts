import { describe, test, expect } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { resolveContact, formatApprovalMessage, registerApprovalTools } from "../src/services/mcp/approval-tools";
import { ApprovalStore } from "../src/services/approval-store";
import { createTestDb, seedTestContact } from "./helpers";
import type { Config } from "../src/config";

const testConfig: Config = {
  port: 3000,
  whatsappVerifyToken: "",
  whatsappAccessToken: "",
  whatsappPhoneNumberId: "",
  telegramBotToken: "",
  pluginApiToken: "test-token",
  databasePath: ":memory:",
  llmApiKey: "",
  llmEndpoint: "",
  agentModel: "",
  agentRequestTimeout: 30000,
};

function createServer(): McpServer {
  return new McpServer(
    { name: "test-server", version: "0.0.1" },
    { capabilities: { tools: {}, resources: {}, prompts: {}, logging: {} } },
  );
}

function getRegisteredTools(server: McpServer): Record<string, any> {
  return (server as any)._registeredTools as Record<string, any>;
}

// ──────────────────────────────────────────────────────────────────────────────
// resolveContact
// ──────────────────────────────────────────────────────────────────────────────

describe("resolveContact", () => {
  test("resolves by platform:id when contact exists", () => {
    const db = createTestDb();
    seedTestContact(db, "whatsapp", "15551234567", "Test User");

    const result = resolveContact(db, "whatsapp:15551234567");
    expect(result).toBe("whatsapp:15551234567");
  });

  test("resolves by display name (case-insensitive)", () => {
    const db = createTestDb();
    seedTestContact(db, "whatsapp", "15551234567", "Test User");

    const result = resolveContact(db, "test user");
    expect(result).toBe("whatsapp:15551234567");
  });

  test("resolves by display name with exact case", () => {
    const db = createTestDb();
    seedTestContact(db, "whatsapp", "15551234567", "Test User");

    const result = resolveContact(db, "Test User");
    expect(result).toBe("whatsapp:15551234567");
  });

  test("throws Contact not found for unknown platform:id", () => {
    const db = createTestDb();

    expect(() => resolveContact(db, "whatsapp:99999999999")).toThrow("Contact not found");
  });

  test("throws Contact not found for unknown display name", () => {
    const db = createTestDb();

    expect(() => resolveContact(db, "Unknown")).toThrow("Contact not found");
  });

  test("throws Multiple contacts match when two contacts share the same display name", () => {
    const db = createTestDb();
    seedTestContact(db, "whatsapp", "11111111111", "John");
    seedTestContact(db, "telegram", "22222222222", "John");

    expect(() => resolveContact(db, "John")).toThrow("Multiple contacts match");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// formatApprovalMessage
// ──────────────────────────────────────────────────────────────────────────────

describe("formatApprovalMessage", () => {
  test("basic question with timeout 300 includes question and 5 minutes", () => {
    const msg = formatApprovalMessage("Do you approve?", undefined, 300);
    expect(msg).toContain("Do you approve?");
    expect(msg).toContain("5 minutes");
  });

  test("includes 'Reply with one of' when options are provided", () => {
    const msg = formatApprovalMessage("Approve this?", ["approve", "reject"], 300);
    expect(msg).toContain("Reply with one of: approve, reject");
  });

  test("does not include 'Reply with' line when no options", () => {
    const msg = formatApprovalMessage("What do you think?", undefined, 300);
    expect(msg).not.toContain("Reply with");
  });

  test("timeout 3600 shows 60 minutes", () => {
    const msg = formatApprovalMessage("Question?", undefined, 3600);
    expect(msg).toContain("60 minutes");
  });

  test("timeout 30 shows 30 seconds", () => {
    const msg = formatApprovalMessage("Quick question?", undefined, 30);
    expect(msg).toContain("30 seconds");
  });

  test("timeout 60 shows 1 minute (singular)", () => {
    const msg = formatApprovalMessage("Question?", undefined, 60);
    expect(msg).toContain("1 minute");
    expect(msg).not.toContain("1 minutes");
  });

  test("includes the automated request header", () => {
    const msg = formatApprovalMessage("Question?");
    expect(msg).toContain("Automated Request");
  });

  test("includes the timer emoji", () => {
    const msg = formatApprovalMessage("Question?", undefined, 300);
    expect(msg).toContain("⏱");
  });

  test("defaults to 5 minutes when timeout not provided", () => {
    const msg = formatApprovalMessage("Question?");
    expect(msg).toContain("5 minutes");
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// registerApprovalTools
// ──────────────────────────────────────────────────────────────────────────────

describe("registerApprovalTools", () => {
  test("registers ask_human tool on the MCP server", () => {
    const server = createServer();
    const db = createTestDb();
    const approvalStore = new ApprovalStore();

    registerApprovalTools(server, () => ({ db, config: testConfig, approvalStore }));

    const tools = getRegisteredTools(server);
    expect("ask_human" in tools).toBe(true);
  });

  test("ask_human tool has expected parameters in schema", () => {
    const server = createServer();
    const db = createTestDb();
    const approvalStore = new ApprovalStore();

    registerApprovalTools(server, () => ({ db, config: testConfig, approvalStore }));

    const tools = getRegisteredTools(server);
    const tool = tools["ask_human"];
    expect(tool).toBeDefined();

    // The MCP SDK stores input schema on the tool
    const inputSchema = tool.inputSchema ?? tool.schema;
    expect(inputSchema).toBeDefined();

    // Check that the schema has the expected properties
    const properties = inputSchema?.properties ?? inputSchema?.shape ?? {};
    const hasContact =
      "contact" in properties ||
      (inputSchema?.shape && "contact" in inputSchema.shape);
    const hasQuestion =
      "question" in properties ||
      (inputSchema?.shape && "question" in inputSchema.shape);

    expect(hasContact).toBe(true);
    expect(hasQuestion).toBe(true);
  });
});
