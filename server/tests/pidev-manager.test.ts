import { describe, test, expect, mock, beforeEach } from "bun:test";
import { PiDevManager } from "../src/services/pidev-manager";
import type { PiDevConfig } from "../src/services/pidev-manager";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function createTestConfig(overrides?: Partial<PiDevConfig>): PiDevConfig {
  return {
    enabled: true,
    installPath: "/usr/local/bin/pi",
    defaultModel: "anthropic/claude-sonnet-4",
    rpcPort: 0,
    maxConcurrentSessions: 3,
    ...overrides,
  };
}

function createMockBridge(connected = true) {
  return {
    isPluginConnected: () => connected,
    sendRequest: mock(async () => ({ name: "test-project" })),
    pendingCount: 0,
  } as any;
}

// ---------------------------------------------------------------------------
// isEnabled
// ---------------------------------------------------------------------------

describe("isEnabled", () => {
  test("isEnabled returns true when enabled", () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig({ enabled: true }));
    expect(manager.isEnabled()).toBe(true);
  });

  test("isEnabled returns false when disabled", () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig({ enabled: false }));
    expect(manager.isEnabled()).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// spawn
// ---------------------------------------------------------------------------

describe("spawn", () => {
  test("spawn creates a session with correct properties", async () => {
    const bridge = createMockBridge();
    const manager = new PiDevManager(bridge, createTestConfig());
    const before = new Date();

    const session = await manager.spawn("my-project", "implement feature X");

    expect(session.project).toBe("my-project");
    expect(session.task).toBe("implement feature X");
    expect(session.status).toBe("running");
    expect(session.id).toMatch(/^pi-\d+-\d+$/);
    expect(session.startedAt).toBeInstanceOf(Date);
    expect(session.startedAt.getTime()).toBeGreaterThanOrEqual(before.getTime());
    expect(Array.isArray(session.output)).toBe(true);
  });

  test("spawn throws when disabled", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig({ enabled: false }));

    await expect(manager.spawn("project", "task")).rejects.toThrow(
      "Pi.dev integration is not enabled",
    );
  });

  test("spawn enforces max concurrent session limit", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig({ maxConcurrentSessions: 2 }));

    await manager.spawn("project", "task 1");
    await manager.spawn("project", "task 2");

    await expect(manager.spawn("project", "task 3")).rejects.toThrow(
      "Maximum concurrent sessions (2) reached",
    );
  });

  test("spawn session starts in running status", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());
    const session = await manager.spawn("project", "task");

    expect(session.status).toBe("running");
  });

  test("spawn loads project context from bridge when connected", async () => {
    const bridge = createMockBridge(true);
    const manager = new PiDevManager(bridge, createTestConfig());

    const session = await manager.spawn("my-project", "task");

    expect(bridge.sendRequest).toHaveBeenCalledWith("project_get", { name: "my-project" });
    expect(session.output.some((line: string) => line.includes("[context] Project loaded"))).toBe(true);
  });

  test("spawn handles bridge not connected gracefully", async () => {
    const bridge = createMockBridge(false);
    const manager = new PiDevManager(bridge, createTestConfig());

    // Should not throw — bridge not connected is handled gracefully
    const session = await manager.spawn("project", "task");

    expect(session.status).toBe("running");
    // sendRequest should not be called when bridge is disconnected
    expect(bridge.sendRequest).not.toHaveBeenCalled();
  });

  test("spawn stores agentProfile from options", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());
    const session = await manager.spawn("project", "task", { agentProfile: "researcher" });

    expect(session.agentProfile).toBe("researcher");
  });
});

// ---------------------------------------------------------------------------
// send
// ---------------------------------------------------------------------------

describe("send", () => {
  test("send delivers message to session", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());
    const session = await manager.spawn("project", "task");

    const result = await manager.send(session.id, "Hello pi");

    expect(result.received).toBe(true);
    expect(result.output).toContain("Hello pi");
    expect(session.output.some((line: string) => line.includes("[send] Hello pi"))).toBe(true);
  });

  test("send throws for non-existent session", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());

    await expect(manager.send("pi-999-0", "Hello")).rejects.toThrow(
      "Session pi-999-0 not found",
    );
  });

  test("send throws for stopped session", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());
    const session = await manager.spawn("project", "task");
    await manager.stop(session.id);

    await expect(manager.send(session.id, "Hello")).rejects.toThrow(
      "is not running",
    );
  });

  test("send appends steering to session output when provided", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());
    const session = await manager.spawn("project", "task");

    await manager.send(session.id, "Do the thing", "focus on tests");

    expect(session.output.some((line: string) => line.includes("[steering] focus on tests"))).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// status
// ---------------------------------------------------------------------------

describe("status", () => {
  test("status returns session by id", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());
    const session = await manager.spawn("project", "task");

    const found = manager.status(session.id);

    expect(found).toBe(session);
    expect(found?.id).toBe(session.id);
  });

  test("status returns undefined for unknown session", () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());

    expect(manager.status("pi-unknown-999")).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// stop
// ---------------------------------------------------------------------------

describe("stop", () => {
  test("stop transitions session to stopped", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());
    const session = await manager.spawn("project", "task");
    expect(session.status).toBe("running");

    const result = await manager.stop(session.id);

    expect(result.stopped).toBe(true);
    expect(session.status).toBe("stopped");
    expect(result.output.some((line: string) => line.includes("stopped"))).toBe(true);
  });

  test("stop throws for non-existent session", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());

    await expect(manager.stop("pi-does-not-exist-0")).rejects.toThrow(
      "Session pi-does-not-exist-0 not found",
    );
  });

  test("stop is idempotent for already-stopped sessions", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());
    const session = await manager.spawn("project", "task");

    const first = await manager.stop(session.id);
    const second = await manager.stop(session.id);

    expect(first.stopped).toBe(true);
    expect(second.stopped).toBe(true);
    expect(session.status).toBe("stopped");
  });
});

// ---------------------------------------------------------------------------
// listSessions
// ---------------------------------------------------------------------------

describe("listSessions", () => {
  test("listSessions returns all sessions", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());
    const s1 = await manager.spawn("project-a", "task 1");
    const s2 = await manager.spawn("project-b", "task 2");
    const s3 = await manager.spawn("project-c", "task 3");

    const all = manager.listSessions();

    expect(all).toHaveLength(3);
    expect(all.map((s) => s.id)).toContain(s1.id);
    expect(all.map((s) => s.id)).toContain(s2.id);
    expect(all.map((s) => s.id)).toContain(s3.id);
  });

  test("listSessions returns empty array when no sessions", () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());

    expect(manager.listSessions()).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// cleanupSessions
// ---------------------------------------------------------------------------

describe("cleanupSessions", () => {
  test("cleanupSessions removes old stopped sessions", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());
    const session = await manager.spawn("project", "task");
    await manager.stop(session.id);

    // Backdate the session so it's considered old
    session.startedAt = new Date(Date.now() - 7_200_000); // 2 hours ago

    const cleaned = manager.cleanupSessions(3_600_000); // 1 hour max age

    expect(cleaned).toBe(1);
    expect(manager.listSessions()).toHaveLength(0);
  });

  test("cleanupSessions does not remove recent stopped sessions", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());
    const session = await manager.spawn("project", "task");
    await manager.stop(session.id);

    // Session was just started — within max age
    const cleaned = manager.cleanupSessions(3_600_000);

    expect(cleaned).toBe(0);
    expect(manager.listSessions()).toHaveLength(1);
  });

  test("cleanupSessions does not remove running sessions", async () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());
    const session = await manager.spawn("project", "task");

    // Backdate — but session is still running
    session.startedAt = new Date(Date.now() - 7_200_000);

    const cleaned = manager.cleanupSessions(3_600_000);

    expect(cleaned).toBe(0);
    expect(manager.listSessions()).toHaveLength(1);
  });

  test("cleanupSessions returns 0 when no sessions qualify", () => {
    const manager = new PiDevManager(createMockBridge(), createTestConfig());

    const cleaned = manager.cleanupSessions(3_600_000);

    expect(cleaned).toBe(0);
  });
});
