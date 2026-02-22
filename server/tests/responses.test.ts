import { describe, test, expect } from "bun:test";
import { successResponse, errorResponse, notFoundResponse } from "../src/helpers/responses";

describe("response helpers", () => {
  test("successResponse", async () => {
    const res = successResponse({ foo: "bar" });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.success).toBe(true);
    expect(body.data.foo).toBe("bar");
  });

  test("successResponse with custom status", async () => {
    const res = successResponse({ id: 1 }, 201);
    expect(res.status).toBe(201);
  });

  test("errorResponse", async () => {
    const res = errorResponse(400, "Bad input");
    expect(res.status).toBe(400);
    const body = await res.json();
    expect(body.success).toBe(false);
    expect(body.error).toBe("Bad input");
  });

  test("notFoundResponse", async () => {
    const res = notFoundResponse("Job not found");
    expect(res.status).toBe(404);
    const body = await res.json();
    expect(body.error).toBe("Job not found");
  });
});
