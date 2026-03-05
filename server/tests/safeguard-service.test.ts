import { describe, test, expect, beforeEach, mock } from "bun:test";
import { SafeguardService } from "../src/services/safeguard-service";
import type { SafeguardPolicy, SafeguardRule } from "../src/services/safeguard-service";
import type { ApprovalStore } from "../src/services/approval-store";

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makePolicy(overrides: Partial<SafeguardPolicy> = {}): SafeguardPolicy {
  return {
    name: "test-policy",
    project: "test",
    level: 1,
    levelName: "standard",
    rules: [],
    ...overrides,
  };
}

function makeBridge(policyOverride?: Partial<SafeguardPolicy>) {
  return {
    isPluginConnected: () => true,
    sendRequest: mock(() =>
      Promise.resolve(makePolicy(policyOverride)),
    ),
  } as any;
}

// ---------------------------------------------------------------------------
// getPolicy
// ---------------------------------------------------------------------------

describe("getPolicy", () => {
  test("returns policy from bridge", async () => {
    const bridge = makeBridge({ name: "my-policy", level: 2, levelName: "guarded" });
    const svc = new SafeguardService(bridge);

    const policy = await svc.getPolicy("test");

    expect(policy.name).toBe("my-policy");
    expect(policy.level).toBe(2);
    expect(bridge.sendRequest).toHaveBeenCalledWith("safeguard_policy_get", { project: "test" }, undefined);
  });

  test("caches policy and does not call bridge again within TTL", async () => {
    const bridge = makeBridge();
    const svc = new SafeguardService(bridge);

    await svc.getPolicy("test");
    await svc.getPolicy("test");

    expect(bridge.sendRequest).toHaveBeenCalledTimes(1);
  });

  test("returns default standard policy on bridge error", async () => {
    const bridge = {
      isPluginConnected: () => true,
      sendRequest: mock(() => Promise.reject(new Error("bridge timeout"))),
    } as any;
    const svc = new SafeguardService(bridge);

    const policy = await svc.getPolicy("missing-project");

    expect(policy.isDefault).toBe(true);
    expect(policy.level).toBe(1);
    expect(policy.levelName).toBe("standard");
    expect(policy.project).toBe("missing-project");
  });
});

// ---------------------------------------------------------------------------
// evaluatePolicy
// ---------------------------------------------------------------------------

describe("evaluatePolicy", () => {
  test("level 0 always allows regardless of rules", async () => {
    const rule: SafeguardRule = { action: "block", description: "block everything", pattern: "delete" };
    const bridge = makeBridge({ level: 0, levelName: "unrestricted", rules: [rule] });
    const svc = new SafeguardService(bridge);

    const result = await svc.evaluatePolicy("test", "delete files", "agent-1", "rm -rf /");

    expect(result.action).toBe("allow");
    expect(result.reason).toContain("Unrestricted");
  });

  test("level 4 always requires approval regardless of rules", async () => {
    const bridge = makeBridge({ level: 4, levelName: "locked", rules: [] });
    const svc = new SafeguardService(bridge);

    const result = await svc.evaluatePolicy("test", "read config", "agent-1", "benign op");

    expect(result.action).toBe("approve");
    expect(result.reason).toContain("Locked");
  });

  test("BLOCK rule matches and blocks operation", async () => {
    const rule: SafeguardRule = {
      action: "block",
      description: "block deletions",
      pattern: "delete",
    };
    const bridge = makeBridge({ level: 1, levelName: "standard", rules: [rule] });
    const svc = new SafeguardService(bridge);

    const result = await svc.evaluatePolicy("test", "delete file", "agent-1", "rm important.txt");

    expect(result.action).toBe("block");
    expect(result.reason).toContain("Blocked by rule");
    expect(result.rule).toBe(rule);
  });

  test("APPROVE rule matches and requires approval", async () => {
    const rule: SafeguardRule = {
      action: "approve",
      description: "deploy requires approval",
      pattern: "deploy",
    };
    const bridge = makeBridge({ level: 1, levelName: "standard", rules: [rule] });
    const svc = new SafeguardService(bridge);

    const result = await svc.evaluatePolicy("test", "deploy service", "agent-1", "deploy to prod");

    expect(result.action).toBe("approve");
    expect(result.reason).toContain("Approval required");
    expect(result.rule).toBe(rule);
  });

  test("LOG rule allows but includes reason", async () => {
    const rule: SafeguardRule = {
      action: "log",
      description: "log reads",
      pattern: "read",
    };
    const bridge = makeBridge({ level: 1, levelName: "standard", rules: [rule] });
    const svc = new SafeguardService(bridge);

    const result = await svc.evaluatePolicy("test", "read config", "agent-1", "read settings");

    expect(result.action).toBe("allow");
    expect(result.reason).toContain("Allowed (logged)");
    expect(result.rule).toBe(rule);
  });

  test("NOTIFY rule allows but includes reason", async () => {
    const rule: SafeguardRule = {
      action: "notify",
      description: "notify on exports",
      pattern: "export",
    };
    const bridge = makeBridge({ level: 2, levelName: "guarded", rules: [rule] });
    const svc = new SafeguardService(bridge);

    const result = await svc.evaluatePolicy("test", "export data", "agent-1", "export to csv");

    expect(result.action).toBe("allow");
    expect(result.reason).toContain("Allowed (logged)");
  });

  test("no matching rule defaults to allow at any standard level", async () => {
    for (const level of [1, 2, 3]) {
      const bridge = makeBridge({ level, levelName: "standard", rules: [] });
      const svc = new SafeguardService(bridge);

      const result = await svc.evaluatePolicy("test", "benign-op", "agent-1", "nothing special");

      expect(result.action).toBe("allow");
      expect(result.reason).toContain("No matching rule");
    }
  });

  test("keyword matching works without explicit pattern", async () => {
    const rule: SafeguardRule = {
      action: "block",
      description: "prevent destructive deletes",
      pattern: null,
    };
    const bridge = makeBridge({ level: 1, levelName: "standard", rules: [rule] });
    const svc = new SafeguardService(bridge);

    // "destructive" and "deletes" are keywords from the description (length > 3)
    const result = await svc.evaluatePolicy("test", "run destructive script", "agent-1", "details");

    expect(result.action).toBe("block");
  });
});

// ---------------------------------------------------------------------------
// requestApproval
// ---------------------------------------------------------------------------

describe("requestApproval", () => {
  test("throws if no approval store configured", async () => {
    const bridge = makeBridge({ contact: "alice" });
    const svc = new SafeguardService(bridge);

    await expect(
      svc.requestApproval("test", "deploy", "agent-1", "deploy to prod"),
    ).rejects.toThrow("Approval store not available");
  });

  test("throws if no contact configured and none passed", async () => {
    const bridge = makeBridge({ contact: undefined });

    const mockStore = {
      create: mock(() => ({ id: "id-1", promise: Promise.resolve({ status: "approved", response: "Approve" }) })),
    } as unknown as ApprovalStore;

    const svc = new SafeguardService(bridge, mockStore);

    await expect(
      svc.requestApproval("test", "deploy", "agent-1", "details"),
    ).rejects.toThrow("No contact configured");
  });

  test("returns approved=true when store resolves with Approve", async () => {
    const bridge = makeBridge({ contact: "alice" });

    // Also mock the audit log call
    const auditBridge = {
      isPluginConnected: () => true,
      sendRequest: mock((op: string) => {
        if (op === "safeguard_policy_get") {
          return Promise.resolve(makePolicy({ contact: "alice" }));
        }
        return Promise.resolve({}); // audit
      }),
    } as any;

    const mockStore = {
      create: mock(() => ({
        id: "approval-id",
        promise: Promise.resolve({ status: "approved", response: "Approve" }),
      })),
    } as unknown as ApprovalStore;

    const svc = new SafeguardService(auditBridge, mockStore);
    const result = await svc.requestApproval("test", "deploy", "agent-1", "details", "alice");

    expect(result.approved).toBe(true);
    expect(result.response).toBe("Approve");
  });

  test("returns approved=false when store resolves with Deny", async () => {
    const auditBridge = {
      isPluginConnected: () => true,
      sendRequest: mock((op: string) => {
        if (op === "safeguard_policy_get") {
          return Promise.resolve(makePolicy({ contact: "alice" }));
        }
        return Promise.resolve({});
      }),
    } as any;

    const mockStore = {
      create: mock(() => ({
        id: "approval-id",
        promise: Promise.resolve({ status: "approved", response: "Deny" }),
      })),
    } as unknown as ApprovalStore;

    const svc = new SafeguardService(auditBridge, mockStore);
    const result = await svc.requestApproval("test", "deploy", "agent-1", "details", "alice");

    expect(result.approved).toBe(false);
  });

  test("auto-denies on timeout with no escalation contact", async () => {
    const auditBridge = {
      isPluginConnected: () => true,
      sendRequest: mock((op: string) => {
        if (op === "safeguard_policy_get") {
          return Promise.resolve(makePolicy({ contact: "alice" }));
        }
        return Promise.resolve({});
      }),
    } as any;

    const mockStore = {
      create: mock(() => ({
        id: "approval-id",
        promise: Promise.resolve({ status: "timeout", response: null }),
      })),
    } as unknown as ApprovalStore;

    const svc = new SafeguardService(auditBridge, mockStore);
    const result = await svc.requestApproval("test", "deploy", "agent-1", "details", "alice");

    expect(result.approved).toBe(false);
    expect(result.response).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// logAudit
// ---------------------------------------------------------------------------

describe("logAudit", () => {
  test("calls bridge with correct params", async () => {
    const bridge = {
      isPluginConnected: () => true,
      sendRequest: mock(() => Promise.resolve({})),
    } as any;
    const svc = new SafeguardService(bridge);

    await svc.logAudit("test", "deploy", "agent-1", "approved", "deploy to prod", "trace-123");

    expect(bridge.sendRequest).toHaveBeenCalledWith(
      "safeguard_audit_append",
      {
        project: "test",
        operation: "deploy",
        agent: "agent-1",
        action: "approved",
        details: "deploy to prod",
      },
      "trace-123",
    );
  });

  test("does not throw when bridge errors", async () => {
    const bridge = {
      isPluginConnected: () => true,
      sendRequest: mock(() => Promise.reject(new Error("bridge down"))),
    } as any;
    const svc = new SafeguardService(bridge);

    // Should not throw
    await expect(
      svc.logAudit("test", "deploy", "agent-1", "approved", "details"),
    ).resolves.toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// invalidateCache
// ---------------------------------------------------------------------------

describe("invalidateCache", () => {
  test("clears specific project cache so bridge is called again", async () => {
    const bridge = makeBridge();
    const svc = new SafeguardService(bridge);

    await svc.getPolicy("project-a");
    await svc.getPolicy("project-b");
    expect(bridge.sendRequest).toHaveBeenCalledTimes(2);

    svc.invalidateCache("project-a");

    await svc.getPolicy("project-a"); // cache miss — re-fetches
    await svc.getPolicy("project-b"); // still cached
    expect(bridge.sendRequest).toHaveBeenCalledTimes(3);
  });

  test("clears all project caches when called without argument", async () => {
    const bridge = makeBridge();
    const svc = new SafeguardService(bridge);

    await svc.getPolicy("project-a");
    await svc.getPolicy("project-b");
    expect(bridge.sendRequest).toHaveBeenCalledTimes(2);

    svc.invalidateCache();

    await svc.getPolicy("project-a");
    await svc.getPolicy("project-b");
    expect(bridge.sendRequest).toHaveBeenCalledTimes(4);
  });
});

// ---------------------------------------------------------------------------
// parseTimeout (tested via requestApproval indirectly, but also via getPolicy)
// ---------------------------------------------------------------------------

describe("parseTimeout (via SafeguardService internals)", () => {
  // We test parseTimeout indirectly through requestApproval by asserting
  // that the store.create is called with the correct timeout value.

  async function getCreateTimeout(autoDenyAfter: string): Promise<number> {
    const auditBridge = {
      isPluginConnected: () => true,
      sendRequest: mock((op: string) => {
        if (op === "safeguard_policy_get") {
          return Promise.resolve(makePolicy({ contact: "alice", autoDenyAfter }));
        }
        return Promise.resolve({});
      }),
    } as any;

    let capturedTimeout = 0;
    const mockStore = {
      create: mock((params: { contactId: string; question: string; options: string[]; timeout: number }) => {
        capturedTimeout = params.timeout;
        return {
          id: "id",
          promise: Promise.resolve({ status: "approved", response: "Approve" }),
        };
      }),
    } as unknown as ApprovalStore;

    const svc = new SafeguardService(auditBridge, mockStore);
    await svc.requestApproval("test", "op", "agent", "details", "alice");
    return capturedTimeout;
  }

  test("parses '1h' to 3600 seconds", async () => {
    const timeout = await getCreateTimeout("1h");
    expect(timeout).toBe(3600);
  });

  test("parses '30m' to 1800 seconds", async () => {
    const timeout = await getCreateTimeout("30m");
    expect(timeout).toBe(1800);
  });

  test("parses '60s' to 60 seconds", async () => {
    const timeout = await getCreateTimeout("60s");
    expect(timeout).toBe(60);
  });

  test("defaults to 3600 for invalid timeout string", async () => {
    const timeout = await getCreateTimeout("bad-value");
    expect(timeout).toBe(3600);
  });
});
