import { describe, test, expect } from "bun:test";
import { WorkClaimStore } from "../src/services/work-store";

describe("WorkClaimStore", () => {
  // -------------------------------------------------------------------------
  // claim()
  // -------------------------------------------------------------------------
  describe("claim()", () => {
    test("stores a new claim successfully", () => {
      const store = new WorkClaimStore();
      const result = store.claim("session-1", "src/api/routes.ts", "Working on routes");
      expect(result.success).toBe(true);
      expect(result.conflict).toBeUndefined();
    });

    test("returns conflict when different session claims same path", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/routes.ts", "Working on routes");
      const result = store.claim("session-2", "src/api/routes.ts", "Also working on routes");
      expect(result.success).toBe(false);
      expect(result.conflict).toBeDefined();
      expect(result.conflict!.sessionId).toBe("session-1");
      expect(result.conflict!.path).toBe("src/api/routes.ts");
    });

    test("allows same session to re-claim (update description)", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/routes.ts", "First description");
      const result = store.claim("session-1", "src/api/routes.ts", "Updated description");
      expect(result.success).toBe(true);
      expect(result.conflict).toBeUndefined();

      const claims = store.listClaims();
      const updated = claims.find((c) => c.path === "src/api/routes.ts");
      expect(updated).toBeDefined();
      expect(updated!.description).toBe("Updated description");
    });

    test("returns conflict when different session claims a file under a wildcard claim", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/**", "Owns all of src/api");
      const result = store.claim("session-2", "src/api/routes.ts", "Specific file");
      expect(result.success).toBe(false);
      expect(result.conflict).toBeDefined();
      expect(result.conflict!.sessionId).toBe("session-1");
    });
  });

  // -------------------------------------------------------------------------
  // release()
  // -------------------------------------------------------------------------
  describe("release()", () => {
    test("removes a claim", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/routes.ts", "Working on routes");
      const released = store.release("session-1", "src/api/routes.ts");
      expect(released).toBe(true);
      expect(store.listClaims()).toHaveLength(0);
    });

    test("returns false for non-existent path", () => {
      const store = new WorkClaimStore();
      const released = store.release("session-1", "nonexistent/path.ts");
      expect(released).toBe(false);
    });

    test("returns false when different session tries to release", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/routes.ts", "Working on routes");
      const released = store.release("session-2", "src/api/routes.ts");
      expect(released).toBe(false);
      // Claim should still exist
      expect(store.listClaims()).toHaveLength(1);
    });
  });

  // -------------------------------------------------------------------------
  // releaseAll()
  // -------------------------------------------------------------------------
  describe("releaseAll()", () => {
    test("removes all claims for a session", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/routes.ts", "Routes");
      store.claim("session-1", "src/api/auth.ts", "Auth");
      store.claim("session-2", "src/db/schema.ts", "DB schema");
      store.releaseAll("session-1");

      const remaining = store.listClaims();
      expect(remaining).toHaveLength(1);
      expect(remaining[0].sessionId).toBe("session-2");
    });

    test("returns correct count of released claims", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/routes.ts", "Routes");
      store.claim("session-1", "src/api/auth.ts", "Auth");
      store.claim("session-1", "src/services/user.ts", "User service");
      const count = store.releaseAll("session-1");
      expect(count).toBe(3);
    });

    test("returns 0 when session has no claims", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/routes.ts", "Routes");
      const count = store.releaseAll("session-2");
      expect(count).toBe(0);
    });
  });

  // -------------------------------------------------------------------------
  // listClaims()
  // -------------------------------------------------------------------------
  describe("listClaims()", () => {
    test("returns all claims", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/routes.ts", "Routes");
      store.claim("session-2", "src/db/schema.ts", "DB schema");
      const claims = store.listClaims();
      expect(claims).toHaveLength(2);
      const paths = claims.map((c) => c.path);
      expect(paths).toContain("src/api/routes.ts");
      expect(paths).toContain("src/db/schema.ts");
    });

    test("returns empty array when no claims", () => {
      const store = new WorkClaimStore();
      expect(store.listClaims()).toEqual([]);
    });

    test("claim entries contain expected fields", () => {
      const before = new Date();
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/routes.ts", "Routes");
      const after = new Date();

      const claims = store.listClaims();
      expect(claims).toHaveLength(1);
      const claim = claims[0];
      expect(claim.path).toBe("src/api/routes.ts");
      expect(claim.sessionId).toBe("session-1");
      expect(claim.description).toBe("Routes");
      expect(claim.claimedAt.getTime()).toBeGreaterThanOrEqual(before.getTime());
      expect(claim.claimedAt.getTime()).toBeLessThanOrEqual(after.getTime());
    });
  });

  // -------------------------------------------------------------------------
  // checkConflict()
  // -------------------------------------------------------------------------
  describe("checkConflict()", () => {
    test("detects wildcard conflict: wildcard claim vs file path", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/**", "Owns all of src/api");
      const result = store.checkConflict("src/api/routes.ts");
      expect(result.hasConflict).toBe(true);
      expect(result.conflictingClaim).toBeDefined();
      expect(result.conflictingClaim!.sessionId).toBe("session-1");
      expect(result.conflictingClaim!.path).toBe("src/api/**");
    });

    test("detects reverse wildcard conflict: file claim vs wildcard check path", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/routes.ts", "Specific file");
      const result = store.checkConflict("src/api/**");
      expect(result.hasConflict).toBe(true);
      expect(result.conflictingClaim).toBeDefined();
      expect(result.conflictingClaim!.sessionId).toBe("session-1");
    });

    test("returns no conflict for unrelated paths", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/routes.ts", "Routes");
      const result = store.checkConflict("src/db/schema.ts");
      expect(result.hasConflict).toBe(false);
      expect(result.conflictingClaim).toBeUndefined();
    });

    test("returns no conflict for unrelated wildcard paths", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/**", "Owns all of src/api");
      const result = store.checkConflict("src/db/schema.ts");
      expect(result.hasConflict).toBe(false);
    });

    test("detects exact match conflict", () => {
      const store = new WorkClaimStore();
      store.claim("session-1", "src/api/routes.ts", "Routes");
      const result = store.checkConflict("src/api/routes.ts");
      expect(result.hasConflict).toBe(true);
      expect(result.conflictingClaim!.description).toBe("Routes");
    });

    test("returns no conflict when store is empty", () => {
      const store = new WorkClaimStore();
      const result = store.checkConflict("src/api/routes.ts");
      expect(result.hasConflict).toBe(false);
    });
  });
});
