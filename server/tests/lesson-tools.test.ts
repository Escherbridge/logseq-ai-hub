import { describe, test, expect, mock } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { registerLessonTools } from "../src/services/mcp/lesson-tools";
import type { McpToolContext } from "../src/types/mcp";
import { createTestDb } from "./helpers";
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

describe("registerLessonTools", () => {
  test("registers without throwing", () => {
    const server = createServer();
    expect(() => {
      registerLessonTools(server, () => ({ db: createTestDb(), config: testConfig }));
    }).not.toThrow();
  });

  test("registers lesson_store and lesson_search tools", () => {
    const server = createServer();
    registerLessonTools(server, () => ({ db: createTestDb(), config: testConfig }));
    const tools = getRegisteredTools(server);
    expect("lesson_store" in tools).toBe(true);
    expect("lesson_search" in tools).toBe(true);
  });

  test("returns error when bridge is not connected", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({}));
    const mockBridge = {
      isPluginConnected: () => false,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerLessonTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["lesson_store"].handler({
      project: "test-project",
      category: "bug-fix",
      title: "Test lesson",
      content: "Detailed content",
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
    expect(sendRequest.mock.calls.length).toBe(0);
  });

  test("returns error when bridge is absent", async () => {
    const server = createServer();
    registerLessonTools(server, () => ({ db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["lesson_search"].handler({ query: "async errors" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("not connected");
  });

  test("lesson_store propagates bridge errors", async () => {
    const server = createServer();
    const sendRequest = mock(async () => {
      throw new Error("lesson storage failed");
    });
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerLessonTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["lesson_store"].handler({
      project: "test-project",
      category: "architecture",
      title: "A lesson",
      content: "Some content",
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("lesson storage failed");
  });

  test("lesson_store calls bridge.sendRequest with correct operation and params", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({ id: "lesson-1", stored: true }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerLessonTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["lesson_store"].handler({
      project: "logseq-ai-hub",
      category: "bug-fix",
      title: "Fix async with-redefs",
      content: "Use set! instead of with-redefs for async code",
    });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("lesson_store");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({
      project: "logseq-ai-hub",
      category: "bug-fix",
      title: "Fix async with-redefs",
    });
  });

  test("lesson_search calls bridge.sendRequest with correct operation and params", async () => {
    const server = createServer();
    const sendRequest = mock(async () => ({ lessons: [], count: 0 }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerLessonTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    await tools["lesson_search"].handler({
      query: "async errors",
      project: "logseq-ai-hub",
      category: "bug-fix",
    });

    expect(sendRequest.mock.calls.length).toBe(1);
    expect(sendRequest.mock.calls[0][0]).toBe("lesson_search");
    expect(sendRequest.mock.calls[0][1]).toMatchObject({
      query: "async errors",
      project: "logseq-ai-hub",
      category: "bug-fix",
    });
  });

  test("lesson_search result is JSON-serialized in content", async () => {
    const server = createServer();
    const lessons = [
      { id: "l1", project: "test-project", category: "bug-fix", title: "A lesson", content: "Details" },
    ];
    const sendRequest = mock(async () => ({ lessons, count: 1 }));
    const mockBridge = {
      isPluginConnected: () => true,
      sendRequest,
      pendingCount: 0,
    } as any;
    registerLessonTools(server, () => ({ bridge: mockBridge, db: createTestDb(), config: testConfig }));

    const tools = getRegisteredTools(server);
    const result = await tools["lesson_search"].handler({ query: "bug" });

    expect(result.isError).toBeUndefined();
    const parsed = JSON.parse(result.content[0].text);
    expect(parsed.count).toBe(1);
    expect(parsed.lessons[0].title).toBe("A lesson");
  });
});
