import { describe, test, expect, beforeEach, mock } from "bun:test";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { Database } from "bun:sqlite";
import { registerEventTools } from "../src/services/mcp/event-tools";
import { createTestDb } from "./helpers";
import { EventBus } from "../src/services/event-bus";
import type { Config } from "../src/config";
import type { McpToolContext } from "../src/types/mcp";

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
  sessionMessageLimit: 50,
  eventRetentionDays: 30,
  httpAllowlist: [],
};

function createServer(): McpServer {
  return new McpServer(
    { name: "test-server", version: "0.0.1" },
    { capabilities: { tools: {}, resources: {}, prompts: {}, logging: {} } },
  );
}

function getRegisteredTools(server: McpServer): Record<string, any> {
  return (server as any)._registeredTools ?? {};
}

describe("registerEventTools", () => {
  let db: Database;
  let eventBus: EventBus;
  let mockSse: { broadcast: (event: any) => void };
  let server: McpServer;

  beforeEach(() => {
    db = createTestDb();
    mockSse = { broadcast: () => {} };
    eventBus = new EventBus(db, mockSse);
    server = createServer();
  });

  test("registers all 7 expected tool names", () => {
    registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
    const tools = getRegisteredTools(server);
    expect("event_publish" in tools).toBe(true);
    expect("event_query" in tools).toBe(true);
    expect("event_subscribe" in tools).toBe(true);
    expect("event_sources" in tools).toBe(true);
    expect("event_recent" in tools).toBe(true);
    expect("webhook_test" in tools).toBe(true);
    expect("http_request" in tools).toBe(true);
  });

  // ── event_publish ────────────────────────────────────────────────────
  describe("event_publish", () => {
    test("publishes event and returns it", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      const result = await tools["event_publish"].handler({
        type: "test.event",
        source: "unit-test",
        data: { key: "value" },
      });

      expect(result.isError).toBeUndefined();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.type).toBe("test.event");
      expect(parsed.source).toBe("unit-test");
      expect(parsed.data).toEqual({ key: "value" });
      expect(parsed.id).toBeDefined();
    });

    test("publishes event with metadata", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      const result = await tools["event_publish"].handler({
        type: "alert.fired",
        source: "monitoring",
        data: { cpu: 95 },
        metadata: { severity: "critical", tags: ["infra"] },
      });

      expect(result.isError).toBeUndefined();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.metadata.severity).toBe("critical");
      expect(parsed.metadata.tags).toEqual(["infra"]);
    });

    test("returns error when eventBus is not available", async () => {
      registerEventTools(server, () => ({ db, config: testConfig }));
      const tools = getRegisteredTools(server);

      const result = await tools["event_publish"].handler({
        type: "test",
        source: "test",
        data: {},
      });

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain("EventBus not available");
    });
  });

  // ── event_query ──────────────────────────────────────────────────────
  describe("event_query", () => {
    test("queries events with no filters", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      // Publish some events first
      eventBus.publish({ type: "alpha", source: "src-a", data: { n: 1 } });
      eventBus.publish({ type: "beta", source: "src-b", data: { n: 2 } });

      const result = await tools["event_query"].handler({});

      expect(result.isError).toBeUndefined();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.total).toBe(2);
      expect(parsed.events.length).toBe(2);
    });

    test("queries events with type filter", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      eventBus.publish({ type: "alpha", source: "src-a", data: { n: 1 } });
      eventBus.publish({ type: "beta", source: "src-b", data: { n: 2 } });
      eventBus.publish({ type: "alpha", source: "src-a", data: { n: 3 } });

      const result = await tools["event_query"].handler({ type: "alpha" });

      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.total).toBe(2);
      expect(parsed.events.every((e: any) => e.type === "alpha")).toBe(true);
    });

    test("queries events with source filter", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      eventBus.publish({ type: "alpha", source: "webhook:github", data: {} });
      eventBus.publish({ type: "beta", source: "webhook:stripe", data: {} });

      const result = await tools["event_query"].handler({ source: "webhook:github" });

      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.total).toBe(1);
      expect(parsed.events[0].source).toBe("webhook:github");
    });

    test("queries events with limit", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      for (let i = 0; i < 5; i++) {
        eventBus.publish({ type: "batch", source: "test", data: { i } });
      }

      const result = await tools["event_query"].handler({ limit: 2 });

      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.events.length).toBe(2);
      expect(parsed.total).toBe(5);
    });

    test("returns error when eventBus is not available", async () => {
      registerEventTools(server, () => ({ db, config: testConfig }));
      const tools = getRegisteredTools(server);

      const result = await tools["event_query"].handler({});

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain("EventBus not available");
    });
  });

  // ── event_subscribe ──────────────────────────────────────────────────
  describe("event_subscribe", () => {
    test("calls bridge.sendRequest with page_create for subscription", async () => {
      const sendRequest = mock(async (_op: string, _params: any) => ({ ok: true }));
      const mockBridge = {
        isPluginConnected: () => true,
        sendRequest,
        pendingCount: 0,
      } as any;

      registerEventTools(server, () => ({
        bridge: mockBridge,
        db,
        config: testConfig,
        eventBus,
      }));
      const tools = getRegisteredTools(server);

      const result = await tools["event_subscribe"].handler({
        name: "my-sub",
        pattern: "webhook.*",
        action: "skill",
        skill: "Skills/alert-handler",
        severityFilter: ["error", "critical"],
      });

      expect(result.isError).toBeUndefined();
      expect(sendRequest.mock.calls.length).toBe(1);
      expect(sendRequest.mock.calls[0][0]).toBe("page_create");
      const params = sendRequest.mock.calls[0][1];
      expect(params.name).toBe("EventSub/my-sub");
      expect(params.properties["event-pattern"]).toBe("webhook.*");
      expect(params.properties["event-action"]).toBe("skill");
      expect(params.properties["event-skill"]).toBe("Skills/alert-handler");
      expect(params.properties["event-severity-filter"]).toBe("error, critical");
    });

    test("includes routeTo property when action is route", async () => {
      const sendRequest = mock(async () => ({ ok: true }));
      const mockBridge = {
        isPluginConnected: () => true,
        sendRequest,
        pendingCount: 0,
      } as any;

      registerEventTools(server, () => ({
        bridge: mockBridge,
        db,
        config: testConfig,
        eventBus,
      }));
      const tools = getRegisteredTools(server);

      await tools["event_subscribe"].handler({
        name: "route-sub",
        pattern: "job.*",
        action: "route",
        routeTo: "whatsapp:15551234567",
      });

      const params = sendRequest.mock.calls[0][1];
      expect(params.properties["event-route-to"]).toBe("whatsapp:15551234567");
    });

    test("returns error when bridge is not connected", async () => {
      const mockBridge = {
        isPluginConnected: () => false,
        sendRequest: mock(async () => ({})),
        pendingCount: 0,
      } as any;

      registerEventTools(server, () => ({
        bridge: mockBridge,
        db,
        config: testConfig,
        eventBus,
      }));
      const tools = getRegisteredTools(server);

      const result = await tools["event_subscribe"].handler({
        name: "test",
        pattern: "*",
        action: "log",
      });

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain("not connected");
    });

    test("returns error when bridge is absent", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      const result = await tools["event_subscribe"].handler({
        name: "test",
        pattern: "*",
        action: "log",
      });

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain("not connected");
    });
  });

  // ── event_sources ────────────────────────────────────────────────────
  describe("event_sources", () => {
    test("returns unique sources from events table", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      eventBus.publish({ type: "a", source: "webhook:github", data: { id: "1" } });
      eventBus.publish({ type: "b", source: "webhook:stripe", data: { id: "2" } });
      eventBus.publish({ type: "c", source: "webhook:github", data: { id: "3" } });

      const result = await tools["event_sources"].handler({});

      expect(result.isError).toBeUndefined();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.count).toBe(2);
      expect(parsed.sources).toContain("webhook:github");
      expect(parsed.sources).toContain("webhook:stripe");
    });

    test("returns empty when no events exist", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      const result = await tools["event_sources"].handler({});

      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.count).toBe(0);
      expect(parsed.sources).toEqual([]);
    });
  });

  // ── event_recent ─────────────────────────────────────────────────────
  describe("event_recent", () => {
    test("returns recent events with default limit of 10", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      for (let i = 0; i < 15; i++) {
        eventBus.publish({ type: "recent.test", source: "test", data: { i } });
      }

      const result = await tools["event_recent"].handler({});

      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.events.length).toBe(10);
      expect(parsed.total).toBe(15);
    });

    test("respects custom limit", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      for (let i = 0; i < 5; i++) {
        eventBus.publish({ type: "recent.test", source: "test", data: { i } });
      }

      const result = await tools["event_recent"].handler({ limit: 3 });

      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.events.length).toBe(3);
      expect(parsed.total).toBe(5);
    });

    test("returns error when eventBus is not available", async () => {
      registerEventTools(server, () => ({ db, config: testConfig }));
      const tools = getRegisteredTools(server);

      const result = await tools["event_recent"].handler({});

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain("EventBus not available");
    });
  });

  // ── webhook_test ─────────────────────────────────────────────────────
  describe("webhook_test", () => {
    test("publishes webhook.test event with prefixed source", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      const result = await tools["webhook_test"].handler({
        source: "github",
        data: { repo: "my-repo", action: "push" },
      });

      expect(result.isError).toBeUndefined();
      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.type).toBe("webhook.test");
      expect(parsed.source).toBe("webhook:github");
      expect(parsed.data).toEqual({ repo: "my-repo", action: "push" });
    });

    test("uses empty data when not provided", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      const result = await tools["webhook_test"].handler({ source: "test" });

      const parsed = JSON.parse(result.content[0].text);
      expect(parsed.data).toEqual({});
    });

    test("event is stored and queryable", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      await tools["webhook_test"].handler({ source: "grafana" });

      const stored = eventBus.query({ type: "webhook.test" });
      expect(stored.total).toBe(1);
      expect(stored.events[0].source).toBe("webhook:grafana");
    });

    test("returns error when eventBus is not available", async () => {
      registerEventTools(server, () => ({ db, config: testConfig }));
      const tools = getRegisteredTools(server);

      const result = await tools["webhook_test"].handler({ source: "test" });

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain("EventBus not available");
    });
  });

  // ── http_request ─────────────────────────────────────────────────────
  describe("http_request", () => {
    test("rejects invalid URL", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      const result = await tools["http_request"].handler({ url: "not a url" });

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain("Invalid URL");
    });

    test("rejects HTTP for non-localhost URLs", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      const result = await tools["http_request"].handler({
        url: "http://example.com/api",
      });

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain("HTTPS is required");
    });

    test("allows HTTP for localhost", async () => {
      // This will likely fail on fetch (no actual server), but should pass URL validation
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      const result = await tools["http_request"].handler({
        url: "http://localhost:9999/nonexistent",
        timeout: 100,
      });

      // Should NOT be an HTTPS error -- it should be a connection error
      expect(result.isError).toBe(true);
      expect(result.content[0].text).not.toContain("HTTPS is required");
    });

    test("allows HTTP for 127.0.0.1", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      const result = await tools["http_request"].handler({
        url: "http://127.0.0.1:9999/nonexistent",
        timeout: 100,
      });

      expect(result.isError).toBe(true);
      expect(result.content[0].text).not.toContain("HTTPS is required");
    });

    test("rejects URL not in allowlist when allowlist is non-empty", async () => {
      const configWithAllowlist = {
        ...testConfig,
        httpAllowlist: ["api.github.com", "*.stripe.com"],
      };

      registerEventTools(server, () => ({
        db,
        config: configWithAllowlist,
        eventBus,
      }));
      const tools = getRegisteredTools(server);

      const result = await tools["http_request"].handler({
        url: "https://evil.example.com/steal",
      });

      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain("not in the HTTP allowlist");
    });

    test("allows URL matching exact allowlist entry", async () => {
      const configWithAllowlist = {
        ...testConfig,
        httpAllowlist: ["api.github.com"],
      };

      registerEventTools(server, () => ({
        db,
        config: configWithAllowlist,
        eventBus,
      }));
      const tools = getRegisteredTools(server);

      // Will fail at fetch level, but should pass allowlist check
      const result = await tools["http_request"].handler({
        url: "https://api.github.com/repos",
        timeout: 100,
      });

      // Should not be an allowlist error
      if (result.isError) {
        expect(result.content[0].text).not.toContain("not in the HTTP allowlist");
      }
    });

    test("allows URL matching wildcard allowlist entry", async () => {
      const configWithAllowlist = {
        ...testConfig,
        httpAllowlist: ["*.stripe.com"],
      };

      registerEventTools(server, () => ({
        db,
        config: configWithAllowlist,
        eventBus,
      }));
      const tools = getRegisteredTools(server);

      const result = await tools["http_request"].handler({
        url: "https://api.stripe.com/v1/charges",
        timeout: 100,
      });

      if (result.isError) {
        expect(result.content[0].text).not.toContain("not in the HTTP allowlist");
      }
    });

    test("allows all URLs when allowlist is empty", async () => {
      registerEventTools(server, () => ({ db, config: testConfig, eventBus }));
      const tools = getRegisteredTools(server);

      // Empty allowlist = all allowed. Will fail at fetch but pass validation.
      const result = await tools["http_request"].handler({
        url: "https://any-domain.example.com/api",
        timeout: 100,
      });

      if (result.isError) {
        expect(result.content[0].text).not.toContain("not in the HTTP allowlist");
      }
    });
  });
});
