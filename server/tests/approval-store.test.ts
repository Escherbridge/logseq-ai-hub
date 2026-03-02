import { describe, test, expect, beforeEach } from "bun:test";
import { ApprovalStore } from "../src/services/approval-store";

describe("ApprovalStore", () => {
  let store: ApprovalStore;

  beforeEach(() => {
    store = new ApprovalStore();
  });

  describe("create", () => {
    test("returns id and promise", () => {
      const result = store.create({ contactId: "contact1", question: "Proceed?" });
      expect(result.id).toBeDefined();
      expect(typeof result.id).toBe("string");
      expect(result.promise).toBeInstanceOf(Promise);
    });

    test("generates a UUID for id", () => {
      const { id } = store.create({ contactId: "contact1", question: "Proceed?" });
      // UUID v4 pattern
      expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    });

    test("stores approval as pending", () => {
      store.create({ contactId: "contact1", question: "Proceed?" });
      expect(store.getPendingCount("contact1")).toBe(1);
    });

    test("sets ISO timestamp for createdAt", () => {
      store.create({ contactId: "contact1", question: "Proceed?" });
      const pending = store.getPending("contact1");
      expect(pending[0].createdAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}/);
      expect(new Date(pending[0].createdAt).getTime()).not.toBeNaN();
    });

    test("stores question correctly", () => {
      store.create({ contactId: "contact1", question: "Is this okay?" });
      const pending = store.getPending("contact1");
      expect(pending[0].question).toBe("Is this okay?");
    });

    test("stores options when provided", () => {
      store.create({ contactId: "contact1", question: "Choose", options: ["yes", "no"] });
      const pending = store.getPending("contact1");
      expect(pending[0].options).toEqual(["yes", "no"]);
    });

    test("uses default timeout of 300 when not specified", () => {
      store.create({ contactId: "contact1", question: "Proceed?" });
      const pending = store.getPending("contact1");
      expect(pending[0].timeout).toBe(300);
    });

    test("uses custom timeout when specified", () => {
      store.create({ contactId: "contact1", question: "Proceed?", timeout: 60 });
      const pending = store.getPending("contact1");
      expect(pending[0].timeout).toBe(60);
    });

    test("status is pending", () => {
      store.create({ contactId: "contact1", question: "Proceed?" });
      const pending = store.getPending("contact1");
      expect(pending[0].status).toBe("pending");
    });

    test("multiple creates for same contact accumulate", () => {
      store.create({ contactId: "contact1", question: "Q1" });
      store.create({ contactId: "contact1", question: "Q2" });
      expect(store.getPendingCount("contact1")).toBe(2);
    });
  });

  describe("resolve (FIFO)", () => {
    test("resolves oldest approval first", async () => {
      const { id: id1, promise: p1 } = store.create({ contactId: "c1", question: "First?" });
      const { id: id2 } = store.create({ contactId: "c1", question: "Second?" });

      const result = store.resolve("c1", "yes");
      expect(result).toEqual({ resolved: true, approvalId: id1 });

      const approval = await p1;
      expect(approval.status).toBe("approved");
      expect(approval.response).toBe("yes");
      expect(approval.resolvedBy).toBe("webhook");

      // second still pending
      expect(store.getPendingCount("c1")).toBe(1);
    });

    test("promise resolves with correct result", async () => {
      const { promise } = store.create({ contactId: "c1", question: "Proceed?" });
      store.resolve("c1", "approved");
      const result = await promise;
      expect(result.status).toBe("approved");
      expect(result.response).toBe("approved");
    });

    test("queue is empty after last resolve", () => {
      store.create({ contactId: "c1", question: "Q?" });
      store.resolve("c1", "yes");
      expect(store.getPendingCount("c1")).toBe(0);
    });

    test("returns false when no pending approvals for contact", () => {
      const result = store.resolve("nonexistent", "yes");
      expect(result).toEqual({ resolved: false });
    });

    test("uses webhook as default resolvedBy", async () => {
      const { promise } = store.create({ contactId: "c1", question: "Q?" });
      store.resolve("c1", "yes");
      const result = await promise;
      expect(result.resolvedBy).toBe("webhook");
    });

    test("supports manual resolvedBy", async () => {
      const { promise } = store.create({ contactId: "c1", question: "Q?" });
      store.resolve("c1", "yes", "manual");
      const result = await promise;
      expect(result.resolvedBy).toBe("manual");
    });
  });

  describe("resolve with options", () => {
    test("case-insensitive match succeeds", async () => {
      const { promise } = store.create({ contactId: "c1", question: "Choose", options: ["Yes", "No"] });
      const result = store.resolve("c1", "yes");
      expect(result).toMatchObject({ resolved: true });
      const approval = await promise;
      expect(approval.status).toBe("approved");
    });

    test("trimmed match succeeds", async () => {
      const { promise } = store.create({ contactId: "c1", question: "Choose", options: ["yes", "no"] });
      const result = store.resolve("c1", "  yes  ");
      expect(result).toMatchObject({ resolved: true });
      const approval = await promise;
      expect(approval.status).toBe("approved");
    });

    test("mismatch returns matched: false", () => {
      store.create({ contactId: "c1", question: "Choose", options: ["yes", "no"] });
      const result = store.resolve("c1", "maybe");
      expect(result).toEqual({ resolved: false, matched: false });
    });

    test("mismatch does not remove approval from queue", () => {
      store.create({ contactId: "c1", question: "Choose", options: ["yes", "no"] });
      store.resolve("c1", "maybe");
      expect(store.getPendingCount("c1")).toBe(1);
    });

    test("no options accepts any response", async () => {
      const { promise } = store.create({ contactId: "c1", question: "Any response?" });
      const result = store.resolve("c1", "anything goes");
      expect(result).toMatchObject({ resolved: true });
      const approval = await promise;
      expect(approval.status).toBe("approved");
      expect(approval.response).toBe("anything goes");
    });

    test("empty options array accepts any response", async () => {
      const { promise } = store.create({ contactId: "c1", question: "Any?", options: [] });
      const result = store.resolve("c1", "freeform");
      expect(result).toMatchObject({ resolved: true });
      const approval = await promise;
      expect(approval.status).toBe("approved");
    });
  });

  describe("timeout", () => {
    test("auto-resolves with status timeout after delay", async () => {
      const { promise } = store.create({ contactId: "c1", question: "Q?", timeout: 0.05 });
      const result = await promise;
      expect(result.status).toBe("timeout");
      expect(result.response).toBeNull();
    });

    test("removes approval from queue on timeout", async () => {
      const { promise } = store.create({ contactId: "c1", question: "Q?", timeout: 0.05 });
      await promise;
      expect(store.getPendingCount("c1")).toBe(0);
    });

    test("timer cleared on early resolve", async () => {
      const { promise } = store.create({ contactId: "c1", question: "Q?", timeout: 0.05 });
      // Resolve before timeout
      store.resolve("c1", "early");
      const result = await promise;
      // Should be approved, not timeout
      expect(result.status).toBe("approved");
    });

    test("early resolve resolves with approved not timeout", async () => {
      const { promise } = store.create({ contactId: "c1", question: "Q?", timeout: 1 });
      store.resolve("c1", "fast");
      const result = await promise;
      expect(result.status).toBe("approved");
      expect(result.response).toBe("fast");
    });
  });

  describe("cancel", () => {
    test("cancels approval by ID", async () => {
      const { id, promise } = store.create({ contactId: "c1", question: "Q?" });
      const cancelled = store.cancel(id);
      expect(cancelled).toBe(true);
      const result = await promise;
      expect(result.status).toBe("cancelled");
      expect(result.response).toBeNull();
    });

    test("removes approval from queue on cancel", async () => {
      const { id, promise } = store.create({ contactId: "c1", question: "Q?" });
      store.cancel(id);
      await promise;
      expect(store.getPendingCount("c1")).toBe(0);
    });

    test("unknown ID returns false", () => {
      const result = store.cancel("00000000-0000-0000-0000-000000000000");
      expect(result).toBe(false);
    });

    test("cancels specific approval when multiple pending", async () => {
      const { id: id1, promise: p1 } = store.create({ contactId: "c1", question: "Q1?" });
      const { id: id2 } = store.create({ contactId: "c1", question: "Q2?" });

      store.cancel(id2);
      expect(store.getPendingCount("c1")).toBe(1);
      expect(store.getPending("c1")[0].id).toBe(id1);
    });

    test("cancelled promise resolves with cancelled status", async () => {
      const { id, promise } = store.create({ contactId: "c1", question: "Q?" });
      store.cancel(id);
      const result = await promise;
      expect(result).toEqual({ status: "cancelled", response: null });
    });
  });

  describe("limits", () => {
    test("6th for same contact throws", () => {
      for (let i = 0; i < 5; i++) {
        store.create({ contactId: "c1", question: `Q${i}?` });
      }
      expect(() => store.create({ contactId: "c1", question: "Q6?" })).toThrow(
        "Max pending approvals (5) reached for contact c1"
      );
    });

    test("does not throw for 5th per contact", () => {
      for (let i = 0; i < 4; i++) {
        store.create({ contactId: "c1", question: `Q${i}?` });
      }
      expect(() => store.create({ contactId: "c1", question: "Q5?" })).not.toThrow();
    });

    test("101st total throws", () => {
      // Spread 100 approvals across 20 contacts (5 each)
      for (let c = 0; c < 20; c++) {
        for (let i = 0; i < 5; i++) {
          store.create({ contactId: `contact${c}`, question: `Q${i}?` });
        }
      }
      expect(() => store.create({ contactId: "new-contact", question: "Over limit?" })).toThrow(
        "Max total pending approvals (100) reached"
      );
    });

    test("different contacts each get their own limit", () => {
      for (let i = 0; i < 5; i++) {
        store.create({ contactId: "c1", question: `Q${i}?` });
      }
      // c2 should still be able to create
      expect(() => store.create({ contactId: "c2", question: "Q?" })).not.toThrow();
    });
  });

  describe("resolveById", () => {
    test("resolves a specific approval by ID", async () => {
      const { id: id1, promise: p1 } = store.create({ contactId: "c1", question: "Q1?" });
      const { id: id2, promise: p2 } = store.create({ contactId: "c1", question: "Q2?" });

      // Resolve second one (not FIFO)
      const resolved = store.resolveById(id2, "specific answer");
      expect(resolved).toBe(true);

      const result = await p2;
      expect(result.status).toBe("approved");
      expect(result.response).toBe("specific answer");
      expect(result.resolvedBy).toBe("manual");

      // First one still pending
      expect(store.getPendingCount("c1")).toBe(1);
      expect(store.getPending("c1")[0].id).toBe(id1);
    });

    test("unknown ID returns false", () => {
      const result = store.resolveById("00000000-0000-0000-0000-000000000000", "answer");
      expect(result).toBe(false);
    });

    test("resolves approval from any contact", async () => {
      const { id: id1, promise: p1 } = store.create({ contactId: "c1", question: "Q1?" });
      const { id: id2, promise: p2 } = store.create({ contactId: "c2", question: "Q2?" });

      store.resolveById(id2, "cross-contact");
      const result = await p2;
      expect(result.status).toBe("approved");
      expect(store.getPendingCount("c2")).toBe(0);
      expect(store.getPendingCount("c1")).toBe(1);
    });

    test("resolvedBy is manual", async () => {
      const { id, promise } = store.create({ contactId: "c1", question: "Q?" });
      store.resolveById(id, "answer");
      const result = await promise;
      expect(result.resolvedBy).toBe("manual");
    });
  });

  describe("getAll", () => {
    test("returns empty array when nothing pending", () => {
      expect(store.getAll()).toEqual([]);
    });

    test("returns all pending across all contacts", () => {
      store.create({ contactId: "c1", question: "Q1?" });
      store.create({ contactId: "c1", question: "Q2?" });
      store.create({ contactId: "c2", question: "Q3?" });

      const all = store.getAll();
      expect(all).toHaveLength(3);
    });

    test("returns only public fields (no resolve/reject/timer)", () => {
      store.create({ contactId: "c1", question: "Q?" });
      const all = store.getAll();
      const item = all[0];
      expect(item).not.toHaveProperty("resolve");
      expect(item).not.toHaveProperty("reject");
      expect(item).not.toHaveProperty("timer");
    });

    test("count decreases after resolve", async () => {
      const { id } = store.create({ contactId: "c1", question: "Q?" });
      store.create({ contactId: "c2", question: "Q?" });

      store.cancel(id);
      expect(store.getAll()).toHaveLength(1);
    });
  });

  describe("getPending / getPendingCount", () => {
    test("getPending returns empty array for unknown contact", () => {
      expect(store.getPending("nobody")).toEqual([]);
    });

    test("getPendingCount returns 0 for unknown contact", () => {
      expect(store.getPendingCount("nobody")).toBe(0);
    });

    test("getPending returns correct items", () => {
      store.create({ contactId: "c1", question: "Q1?" });
      store.create({ contactId: "c1", question: "Q2?" });

      const pending = store.getPending("c1");
      expect(pending).toHaveLength(2);
      expect(pending[0].question).toBe("Q1?");
      expect(pending[1].question).toBe("Q2?");
    });

    test("getPendingCount is correct after create", () => {
      store.create({ contactId: "c1", question: "Q?" });
      store.create({ contactId: "c1", question: "Q2?" });
      expect(store.getPendingCount("c1")).toBe(2);
    });

    test("getPendingCount decreases after resolve", () => {
      store.create({ contactId: "c1", question: "Q?" });
      store.create({ contactId: "c1", question: "Q2?" });
      store.resolve("c1", "yes");
      expect(store.getPendingCount("c1")).toBe(1);
    });

    test("getPendingCount decreases after cancel", async () => {
      const { id } = store.create({ contactId: "c1", question: "Q?" });
      store.create({ contactId: "c1", question: "Q2?" });
      const p = store.cancel(id);
      expect(store.getPendingCount("c1")).toBe(1);
    });

    test("getPending returns only public fields", () => {
      store.create({ contactId: "c1", question: "Q?" });
      const pending = store.getPending("c1");
      const item = pending[0];
      expect(item).not.toHaveProperty("resolve");
      expect(item).not.toHaveProperty("reject");
      expect(item).not.toHaveProperty("timer");
    });
  });
});
