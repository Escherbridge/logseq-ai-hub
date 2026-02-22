import { describe, test, expect } from "bun:test";
import { matchRoute } from "../src/router/match";

describe("matchRoute", () => {
  test("exact match", () => {
    expect(matchRoute("/health", "/health")).toEqual({});
  });

  test("no match", () => {
    expect(matchRoute("/health", "/other")).toBeNull();
  });

  test("single param", () => {
    expect(matchRoute("/api/jobs/:id", "/api/jobs/my-job")).toEqual({ id: "my-job" });
  });

  test("multi-segment param", () => {
    expect(matchRoute("/api/mcp/servers/:id/tools", "/api/mcp/servers/fs/tools")).toEqual({ id: "fs" });
  });

  test("different length paths don't match", () => {
    expect(matchRoute("/api/jobs/:id", "/api/jobs")).toBeNull();
  });

  test("trailing segments differ", () => {
    expect(matchRoute("/api/jobs/:id/start", "/api/jobs/foo/cancel")).toBeNull();
  });
});
