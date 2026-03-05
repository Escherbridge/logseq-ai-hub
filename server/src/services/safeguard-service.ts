import type { AgentBridge } from "./agent-bridge";
import type { ApprovalStore } from "./approval-store";

export interface SafeguardRule {
  action: "block" | "approve" | "log" | "notify";
  description: string;
  pattern: string | null;
}

export interface SafeguardPolicy {
  name: string;
  project: string;
  level: number;
  levelName: string;
  contact?: string;
  escalationContact?: string;
  reviewInterval?: string;
  autoDenyAfter?: string;
  rules: SafeguardRule[];
  isDefault?: boolean;
}

export interface SafeguardCheckResult {
  action: "allow" | "block" | "approve";
  reason: string;
  rule?: SafeguardRule;
  policy: SafeguardPolicy;
}

export interface SafeguardAuditEntry {
  timestamp: string;
  project: string;
  operation: string;
  agent: string;
  action: string;
  details: string;
}

const LEVEL_NAMES: Record<number, string> = {
  0: "unrestricted",
  1: "standard",
  2: "guarded",
  3: "supervised",
  4: "locked",
};

export class SafeguardService {
  private bridge: AgentBridge;
  private approvalStore: ApprovalStore | undefined;
  private policyCache: Map<string, { policy: SafeguardPolicy; cachedAt: number }> = new Map();
  private readonly CACHE_TTL_MS = 60_000; // 1 minute

  constructor(bridge: AgentBridge, approvalStore?: ApprovalStore) {
    this.bridge = bridge;
    this.approvalStore = approvalStore;
  }

  /**
   * Fetch the safeguard policy for a project.
   * Uses cache with 1-minute TTL.
   */
  async getPolicy(project: string, traceId?: string): Promise<SafeguardPolicy> {
    const cached = this.policyCache.get(project);
    if (cached && Date.now() - cached.cachedAt < this.CACHE_TTL_MS) {
      return cached.policy;
    }

    try {
      const result = await this.bridge.sendRequest("safeguard_policy_get", { project }, traceId);
      const policy = result as SafeguardPolicy;
      this.policyCache.set(project, { policy, cachedAt: Date.now() });
      return policy;
    } catch {
      // Default to standard policy
      const defaultPolicy: SafeguardPolicy = {
        name: "default",
        project,
        level: 1,
        levelName: "standard",
        rules: [],
        isDefault: true,
      };
      this.policyCache.set(project, { policy: defaultPolicy, cachedAt: Date.now() });
      return defaultPolicy;
    }
  }

  /**
   * Evaluate an operation against the project's safeguard policy.
   * Returns allow/block/approve with reason.
   */
  async evaluatePolicy(
    project: string,
    operation: string,
    agent: string,
    details: string,
    traceId?: string,
  ): Promise<SafeguardCheckResult> {
    const policy = await this.getPolicy(project, traceId);

    // Level 0: unrestricted — always allow
    if (policy.level === 0) {
      return { action: "allow", reason: "Unrestricted mode — all operations allowed", policy };
    }

    // Level 4: locked — everything requires approval
    if (policy.level === 4) {
      return { action: "approve", reason: "Locked mode — all operations require approval", policy };
    }

    // Check rules for matching operations
    for (const rule of policy.rules) {
      if (this.operationMatchesRule(operation, details, rule)) {
        if (rule.action === "block") {
          return { action: "block", reason: `Blocked by rule: ${rule.description}`, rule, policy };
        }
        if (rule.action === "approve") {
          return { action: "approve", reason: `Approval required: ${rule.description}`, rule, policy };
        }
        // "log" and "notify" — allow but log
        return { action: "allow", reason: `Allowed (logged): ${rule.description}`, rule, policy };
      }
    }

    // No matching rule — behavior depends on level
    // Level 1 (standard): allow by default
    // Level 2 (guarded): allow if no explicit restriction matched
    // Level 3 (supervised): allow but will be logged
    return {
      action: "allow",
      reason: `No matching rule — default allow at level ${policy.level} (${policy.levelName})`,
      policy,
    };
  }

  /**
   * Request human approval for a blocked/requires-approval operation.
   * Integrates with the existing ApprovalStore from the HITL system.
   */
  async requestApproval(
    project: string,
    operation: string,
    agent: string,
    details: string,
    contact?: string,
    traceId?: string,
  ): Promise<{ approved: boolean; response: string | null }> {
    if (!this.approvalStore) {
      throw new Error("Approval store not available");
    }

    const policy = await this.getPolicy(project, traceId);
    const resolvedContact = contact || policy.contact;

    if (!resolvedContact) {
      throw new Error("No contact configured for approval requests");
    }

    const question = `Safeguard approval needed for project "${project}":\n\nOperation: ${operation}\nAgent: ${agent}\nDetails: ${details}`;
    const options = ["Approve", "Deny"];

    // Parse timeout from policy auto-deny-after (e.g., "1h" → 3600 seconds)
    const timeout = this.parseTimeout(policy.autoDenyAfter || "1h");

    const { promise } = this.approvalStore.create({
      contactId: resolvedContact,
      question,
      options,
      timeout,
    });

    const result = await promise;
    const approved = result.status === "approved" && result.response?.toLowerCase() === "approve";

    if (result.status === "timeout") {
      // Timeout — check for escalation contact
      if (policy.escalationContact && policy.escalationContact !== resolvedContact) {
        const escalationTimeout = this.parseTimeout(policy.autoDenyAfter || "1h");
        const { promise: escPromise } = this.approvalStore.create({
          contactId: policy.escalationContact,
          question: `ESCALATION: ${question}`,
          options,
          timeout: escalationTimeout,
        });

        const escResult = await escPromise;
        const escApproved =
          escResult.status === "approved" && escResult.response?.toLowerCase() === "approve";

        if (escResult.status === "timeout") {
          await this.logAudit(project, operation, agent, "auto-denied", details, traceId);
          return { approved: false, response: null };
        }

        await this.logAudit(
          project,
          operation,
          agent,
          escApproved ? "approved-escalated" : "denied-escalated",
          details,
          traceId,
        );
        return { approved: escApproved, response: escResult.response };
      }

      await this.logAudit(project, operation, agent, "auto-denied", details, traceId);
      return { approved: false, response: null };
    }

    await this.logAudit(
      project,
      operation,
      agent,
      approved ? "approved" : "denied",
      details,
      traceId,
    );
    return { approved, response: result.response };
  }

  /**
   * Log an audit entry to the project's safeguard log page.
   */
  async logAudit(
    project: string,
    operation: string,
    agent: string,
    action: string,
    details: string,
    traceId?: string,
  ): Promise<void> {
    try {
      await this.bridge.sendRequest(
        "safeguard_audit_append",
        { project, operation, agent, action, details },
        traceId,
      );
    } catch (err) {
      console.warn("Failed to log safeguard audit:", err);
    }
  }

  /** Invalidate cached policy for a project */
  invalidateCache(project?: string): void {
    if (project) {
      this.policyCache.delete(project);
    } else {
      this.policyCache.clear();
    }
  }

  /**
   * Check if an operation description matches a rule.
   * Simple keyword matching for now — can be extended with glob patterns later.
   */
  private operationMatchesRule(operation: string, details: string, rule: SafeguardRule): boolean {
    const combined = `${operation} ${details}`.toLowerCase();
    const ruleDesc = rule.description.toLowerCase();

    // If rule has a pattern (glob), check if details contain it
    if (rule.pattern) {
      return combined.includes(rule.pattern.toLowerCase());
    }

    // Otherwise, check if key words from rule description appear in operation
    const keywords = ruleDesc.split(/\s+/).filter((w) => w.length > 3);
    return keywords.some((kw) => combined.includes(kw));
  }

  /**
   * Parse timeout string like "1h", "30m", "2h" to seconds.
   */
  private parseTimeout(timeoutStr: string): number {
    const match = timeoutStr.match(/^(\d+)(m|h|s)?$/);
    if (!match) return 3600; // default 1 hour
    const value = parseInt(match[1], 10);
    const unit = match[2] || "s";
    switch (unit) {
      case "h":
        return value * 3600;
      case "m":
        return value * 60;
      default:
        return value;
    }
  }
}
