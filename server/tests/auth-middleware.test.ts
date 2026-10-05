import { describe, test, expect } from "bun:test";
import { authenticate, unauthorizedResponse } from "../src/middleware/auth";
import { makeTestConfig } from "./helpers";

const config = makeTestConfig();

describe("authenticate", () => {
  test("valid token", () => {
    const req = new Request("http://localhost/api/test", {
      headers: { Authorization: "Bearer test-token" },
    });
    expect(authenticate(req, config)).toBe(true);
  });

  test("invalid token", () => {
    const req = new Request("http://localhost/api/test", {
      headers: { Authorization: "Bearer wrong-token" },
    });
    expect(authenticate(req, config)).toBe(false);
  });

  test("missing header", () => {
    const req = new Request("http://localhost/api/test");
    expect(authenticate(req, config)).toBe(false);
  });
});

describe("unauthorizedResponse", () => {
  test("returns 401", async () => {
    const res = unauthorizedResponse();
    expect(res.status).toBe(401);
    const body = await res.json();
    expect(body.success).toBe(false);
    expect(body.error).toBe("Unauthorized");
  });
});
