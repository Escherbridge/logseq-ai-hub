import type { ApprovalResult, CreateApprovalParams, PendingApproval, ApprovalRequest } from "../types/approval";
import { sseManager } from "./sse";

const DEFAULT_TIMEOUT = 300; // 5 minutes
const MAX_PER_CONTACT = 5;
const MAX_TOTAL = 100;

export class ApprovalStore {
  private pendingByContact = new Map<string, PendingApproval[]>();

  /**
   * Creates an approval request and returns a Promise that resolves
   * when the approval is resolved (by webhook, manual, or timeout).
   */
  create(params: CreateApprovalParams): { id: string; promise: Promise<ApprovalResult> } {
    // Check limits
    const contactQueue = this.pendingByContact.get(params.contactId) || [];
    if (contactQueue.length >= MAX_PER_CONTACT) {
      throw new Error(`Max pending approvals (${MAX_PER_CONTACT}) reached for contact ${params.contactId}`);
    }
    if (this.totalPending() >= MAX_TOTAL) {
      throw new Error(`Max total pending approvals (${MAX_TOTAL}) reached`);
    }

    const id = crypto.randomUUID();
    const timeout = params.timeout ?? DEFAULT_TIMEOUT;

    let resolvePromise!: (result: ApprovalResult) => void;
    let rejectPromise!: (reason: Error) => void;

    const promise = new Promise<ApprovalResult>((resolve, reject) => {
      resolvePromise = resolve;
      rejectPromise = reject;
    });

    const timer = setTimeout(() => {
      const wasStillPending = this.removeApproval(id);
      if (!wasStillPending) return; // Already resolved/cancelled — nothing to do
      resolvePromise({ status: "timeout", response: null });
      sseManager.broadcast({
        type: "approval_timeout",
        data: { approvalId: id, contactId: params.contactId },
      });
    }, timeout * 1000);

    const approval: PendingApproval = {
      id,
      contactId: params.contactId,
      question: params.question,
      options: params.options,
      timeout,
      createdAt: new Date().toISOString(),
      status: "pending",
      response: null,
      resolve: resolvePromise,
      reject: rejectPromise,
      timer,
    };

    if (!this.pendingByContact.has(params.contactId)) {
      this.pendingByContact.set(params.contactId, []);
    }
    this.pendingByContact.get(params.contactId)!.push(approval);

    sseManager.broadcast({
      type: "approval_created",
      data: { approvalId: id, contactId: params.contactId, question: params.question, timeout },
    });

    return { id, promise };
  }

  /**
   * Resolve the oldest pending approval for a contact (FIFO).
   * If options are set, validates the response matches one of them.
   * Returns { resolved: true } or { resolved: false, matched: false } for options mismatch.
   */
  resolve(
    contactId: string,
    response: string,
    resolvedBy: "webhook" | "manual" = "webhook"
  ): { resolved: true; approvalId: string } | { resolved: false; matched?: false } {
    const queue = this.pendingByContact.get(contactId);
    if (!queue || queue.length === 0) return { resolved: false };

    const oldest = queue[0]; // FIFO

    // Options validation
    if (oldest.options && oldest.options.length > 0) {
      const trimmed = response.trim().toLowerCase();
      const match = oldest.options.some((opt) => opt.toLowerCase() === trimmed);
      if (!match) return { resolved: false, matched: false };
    }

    // Resolve it
    clearTimeout(oldest.timer);
    queue.shift();
    if (queue.length === 0) this.pendingByContact.delete(contactId);

    oldest.resolve({ status: "approved", response, resolvedBy });
    sseManager.broadcast({
      type: "approval_resolved",
      data: { approvalId: oldest.id, contactId, response, resolvedBy },
    });
    return { resolved: true, approvalId: oldest.id };
  }

  /**
   * Resolve a specific approval by its ID (for manual/admin resolution).
   */
  resolveById(approvalId: string, response: string): boolean {
    for (const [contactId, queue] of this.pendingByContact) {
      const idx = queue.findIndex((a) => a.id === approvalId);
      if (idx !== -1) {
        const approval = queue[idx];
        clearTimeout(approval.timer);
        queue.splice(idx, 1);
        if (queue.length === 0) this.pendingByContact.delete(contactId);
        approval.resolve({ status: "approved", response, resolvedBy: "manual" });
        sseManager.broadcast({
          type: "approval_resolved",
          data: { approvalId, contactId, response, resolvedBy: "manual" },
        });
        return true;
      }
    }
    return false;
  }

  /**
   * Cancel a pending approval by ID.
   */
  cancel(approvalId: string): boolean {
    for (const [contactId, queue] of this.pendingByContact) {
      const idx = queue.findIndex((a) => a.id === approvalId);
      if (idx !== -1) {
        const approval = queue[idx];
        clearTimeout(approval.timer);
        queue.splice(idx, 1);
        if (queue.length === 0) this.pendingByContact.delete(contactId);
        approval.resolve({ status: "cancelled", response: null });
        return true;
      }
    }
    return false;
  }

  getPending(contactId: string): ApprovalRequest[] {
    return (this.pendingByContact.get(contactId) || []).map(this.toPublic);
  }

  getPendingCount(contactId: string): number {
    return (this.pendingByContact.get(contactId) || []).length;
  }

  getAll(): ApprovalRequest[] {
    const all: ApprovalRequest[] = [];
    for (const queue of this.pendingByContact.values()) {
      all.push(...queue.map(this.toPublic));
    }
    return all;
  }

  private totalPending(): number {
    let count = 0;
    for (const queue of this.pendingByContact.values()) {
      count += queue.length;
    }
    return count;
  }

  private removeApproval(id: string): boolean {
    for (const [contactId, queue] of this.pendingByContact) {
      const idx = queue.findIndex((a) => a.id === id);
      if (idx !== -1) {
        queue.splice(idx, 1);
        if (queue.length === 0) this.pendingByContact.delete(contactId);
        return true;
      }
    }
    return false;
  }

  private toPublic(approval: PendingApproval): ApprovalRequest {
    return {
      id: approval.id,
      contactId: approval.contactId,
      question: approval.question,
      options: approval.options,
      timeout: approval.timeout,
      createdAt: approval.createdAt,
      status: "pending",
      response: null,
    };
  }
}
