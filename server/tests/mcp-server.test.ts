import { describe, test, expect } from "bun:test";
import {
  createMcpServer,
  getMcpServer,
  getMcpStatus,
  onSessionInitialized,
  onSessionClosed,
} from "../src/services/mcp-server";

/**
 * mcp-server uses module-level singletons. When tests run in a single process
 * all test files share the same module cache, so we can't guarantee "null"
 * state across files. We test the observable contract of each export instead.
 */

describe("createMcpServer", () => {
  test("returns an McpServer instance with tool/resource/prompt methods", () => {
    const server = createMcpServer();
    expect(server).not.toBeNull();
    expect(typeof server.tool).toBe("function");
    expect(typeof server.resource).toBe("function");
    expect(typeof server.prompt).toBe("function");
  });

  test("is idempotent - repeated calls return the exact same instance", () => {
    const first = createMcpServer();
    const second = createMcpServer();
    const third = createMcpServer();
    expect(first).toBe(second);
    expect(second).toBe(third);
  });
});

describe("getMcpServer", () => {
  test("returns the same instance as createMcpServer after creation", () => {
    const created = createMcpServer();
    const gotten = getMcpServer();
    expect(gotten).toBe(created);
    expect(gotten).not.toBeNull();
  });
});

describe("getMcpStatus - initial zero counts", () => {
  test("returns an object with activeSessions, toolCount, resourceCount, promptCount", () => {
    const status = getMcpStatus();
    expect(typeof status.activeSessions).toBe("number");
    expect(typeof status.toolCount).toBe("number");
    expect(typeof status.resourceCount).toBe("number");
    expect(typeof status.promptCount).toBe("number");
  });

  test("toolCount is non-negative", () => {
    expect(getMcpStatus().toolCount).toBeGreaterThanOrEqual(0);
  });

  test("resourceCount is non-negative", () => {
    expect(getMcpStatus().resourceCount).toBeGreaterThanOrEqual(0);
  });

  test("promptCount is non-negative", () => {
    expect(getMcpStatus().promptCount).toBeGreaterThanOrEqual(0);
  });
});

describe("onSessionInitialized / onSessionClosed", () => {
  test("onSessionInitialized increments activeSessions by 1", () => {
    const before = getMcpStatus().activeSessions;
    onSessionInitialized("session-test-1");
    expect(getMcpStatus().activeSessions).toBe(before + 1);
    // cleanup
    onSessionClosed("session-test-1");
  });

  test("onSessionClosed decrements activeSessions by 1", () => {
    onSessionInitialized("session-test-2");
    const before = getMcpStatus().activeSessions;
    onSessionClosed("session-test-2");
    expect(getMcpStatus().activeSessions).toBe(before - 1);
  });

  test("onSessionClosed does not go below zero", () => {
    // Drain to zero first
    for (let i = 0; i < 50; i++) onSessionClosed("phantom");
    expect(getMcpStatus().activeSessions).toBe(0);
  });

  test("multiple sessions track correctly", () => {
    // Ensure starting from zero
    for (let i = 0; i < 50; i++) onSessionClosed("reset");
    expect(getMcpStatus().activeSessions).toBe(0);

    onSessionInitialized("a");
    onSessionInitialized("b");
    onSessionInitialized("c");
    expect(getMcpStatus().activeSessions).toBe(3);

    onSessionClosed("a");
    expect(getMcpStatus().activeSessions).toBe(2);

    onSessionClosed("b");
    onSessionClosed("c");
    expect(getMcpStatus().activeSessions).toBe(0);
  });

  test("session count stays at zero after balanced open/close cycle", () => {
    for (let i = 0; i < 50; i++) onSessionClosed("reset");

    const ids = ["s1", "s2", "s3", "s4", "s5"];
    for (const id of ids) onSessionInitialized(id);
    expect(getMcpStatus().activeSessions).toBe(ids.length);

    for (const id of ids) onSessionClosed(id);
    expect(getMcpStatus().activeSessions).toBe(0);
  });
});
