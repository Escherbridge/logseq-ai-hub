import { describe, test, expect, mock } from "bun:test";
import { ApprovalStore } from "../src/services/approval-store";
import { checkAndResolveApproval } from "../src/services/approval-correlation";

// Helper to create a store and an approval, returns { store, id, promise }
function makeStore() {
  return new ApprovalStore();
}

describe("checkAndResolveApproval", () => {
  // ------------------------------------------------------------------ //
  // 1. No pending approvals
  // ------------------------------------------------------------------ //
  describe("no pending approvals", () => {
    test("returns { resolved: false } when contact has no pending approvals", async () => {
      const store = makeStore();
      const sendFollowUp = mock(async () => {});

      const result = await checkAndResolveApproval(store, "whatsapp:15551234567", "yes", sendFollowUp);

      expect(result).toEqual({ resolved: false });
    });

    test("sendFollowUp is NOT called when no pending approvals", async () => {
      const store = makeStore();
      const sendFollowUp = mock(async () => {});

      await checkAndResolveApproval(store, "whatsapp:15551234567", "yes", sendFollowUp);

      expect(sendFollowUp).not.toHaveBeenCalled();
    });
  });

  // ------------------------------------------------------------------ //
  // 2. Pending approval without options
  // ------------------------------------------------------------------ //
  describe("pending approval without options", () => {
    test("returns { resolved: true, approvalId } when approval is resolved", async () => {
      const store = makeStore();
      const contactId = "whatsapp:15551234567";
      const { id } = store.create({ contactId, question: "Should I proceed?" });

      const result = await checkAndResolveApproval(store, contactId, "yes");

      expect(result.resolved).toBe(true);
      expect(result.approvalId).toBe(id);
    });

    test("approval Promise resolves with approved status and response", async () => {
      const store = makeStore();
      const contactId = "whatsapp:15551234567";
      const { promise } = store.create({ contactId, question: "Proceed?" });

      await checkAndResolveApproval(store, contactId, "yes");

      const approvalResult = await promise;
      expect(approvalResult.status).toBe("approved");
      expect(approvalResult.response).toBe("yes");
      expect(approvalResult.resolvedBy).toBe("webhook");
    });

    test("no more pending approvals after resolution", async () => {
      const store = makeStore();
      const contactId = "whatsapp:15551234567";
      store.create({ contactId, question: "Proceed?" });

      await checkAndResolveApproval(store, contactId, "yes");

      expect(store.getPendingCount(contactId)).toBe(0);
    });
  });

  // ------------------------------------------------------------------ //
  // 3. Pending approval with options - match
  // ------------------------------------------------------------------ //
  describe("pending approval with options - match", () => {
    test("resolves when message exactly matches an option", async () => {
      const store = makeStore();
      const contactId = "whatsapp:15551234567";
      const { id } = store.create({ contactId, question: "Approve?", options: ["approve", "reject"] });

      const result = await checkAndResolveApproval(store, contactId, "approve");

      expect(result.resolved).toBe(true);
      expect(result.approvalId).toBe(id);
    });

    test("resolves case insensitively (APPROVE matches 'approve')", async () => {
      const store = makeStore();
      const contactId = "whatsapp:15551234567";
      const { id } = store.create({ contactId, question: "Approve?", options: ["approve", "reject"] });

      const result = await checkAndResolveApproval(store, contactId, "APPROVE");

      expect(result.resolved).toBe(true);
      expect(result.approvalId).toBe(id);
    });

    test("resolves when message has surrounding whitespace ('  approve  ' matches 'approve')", async () => {
      const store = makeStore();
      const contactId = "whatsapp:15551234567";
      const { id } = store.create({ contactId, question: "Approve?", options: ["approve", "reject"] });

      const result = await checkAndResolveApproval(store, contactId, "  approve  ");

      expect(result.resolved).toBe(true);
      expect(result.approvalId).toBe(id);
    });
  });

  // ------------------------------------------------------------------ //
  // 4. Pending approval with options - mismatch
  // ------------------------------------------------------------------ //
  describe("pending approval with options - mismatch", () => {
    test("returns { resolved: false, optionsMismatch: true } on mismatch", async () => {
      const store = makeStore();
      const contactId = "whatsapp:15551234567";
      store.create({ contactId, question: "Approve?", options: ["approve", "reject"] });

      const result = await checkAndResolveApproval(store, contactId, "maybe");

      expect(result.resolved).toBe(false);
      expect(result.optionsMismatch).toBe(true);
    });

    test("sendFollowUp IS called with message containing options on mismatch", async () => {
      const store = makeStore();
      const contactId = "whatsapp:15551234567";
      store.create({ contactId, question: "Approve?", options: ["approve", "reject"] });
      const sendFollowUp = mock(async (_cid: string, _msg: string) => {});

      await checkAndResolveApproval(store, contactId, "maybe", sendFollowUp);

      expect(sendFollowUp).toHaveBeenCalledTimes(1);
      const [calledContactId, calledMessage] = sendFollowUp.mock.calls[0] as [string, string];
      expect(calledContactId).toBe(contactId);
      expect(calledMessage).toContain("approve");
      expect(calledMessage).toContain("reject");
    });

    test("approval is still pending (not consumed) after mismatch", async () => {
      const store = makeStore();
      const contactId = "whatsapp:15551234567";
      store.create({ contactId, question: "Approve?", options: ["approve", "reject"] });

      await checkAndResolveApproval(store, contactId, "maybe");

      expect(store.getPendingCount(contactId)).toBe(1);
    });
  });

  // ------------------------------------------------------------------ //
  // 5. Multiple pending (FIFO)
  // ------------------------------------------------------------------ //
  describe("multiple pending approvals (FIFO)", () => {
    test("resolves the oldest approval first", async () => {
      const store = makeStore();
      const contactId = "whatsapp:15551234567";
      const { id: firstId } = store.create({ contactId, question: "First?" });
      store.create({ contactId, question: "Second?" });

      const result = await checkAndResolveApproval(store, contactId, "yes");

      expect(result.resolved).toBe(true);
      expect(result.approvalId).toBe(firstId);
    });

    test("second approval is still pending after first is resolved", async () => {
      const store = makeStore();
      const contactId = "whatsapp:15551234567";
      store.create({ contactId, question: "First?" });
      const { id: secondId } = store.create({ contactId, question: "Second?" });

      await checkAndResolveApproval(store, contactId, "yes");

      expect(store.getPendingCount(contactId)).toBe(1);
      const remaining = store.getPending(contactId);
      expect(remaining[0].id).toBe(secondId);
    });
  });

  // ------------------------------------------------------------------ //
  // 6. Different contacts don't interfere
  // ------------------------------------------------------------------ //
  describe("different contacts", () => {
    test("message from contact B does not resolve contact A's approval", async () => {
      const store = makeStore();
      const contactA = "whatsapp:11111111111";
      const contactB = "whatsapp:22222222222";
      store.create({ contactId: contactA, question: "Approve?" });

      const result = await checkAndResolveApproval(store, contactB, "yes");

      expect(result.resolved).toBe(false);
      // Contact A's approval is still pending
      expect(store.getPendingCount(contactA)).toBe(1);
    });
  });

  // ------------------------------------------------------------------ //
  // 7. sendFollowUp is optional (no callback provided)
  // ------------------------------------------------------------------ //
  describe("sendFollowUp is optional", () => {
    test("options mismatch with no sendFollowUp callback does not throw", async () => {
      const store = makeStore();
      const contactId = "whatsapp:15551234567";
      store.create({ contactId, question: "Approve?", options: ["approve", "reject"] });

      // No sendFollowUp passed
      const result = await checkAndResolveApproval(store, contactId, "maybe");

      expect(result.resolved).toBe(false);
      expect(result.optionsMismatch).toBe(true);
    });
  });
});
