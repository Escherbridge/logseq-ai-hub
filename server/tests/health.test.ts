import { describe, it, expect } from "bun:test";
import { handleHealth } from "../src/routes/health";
import { loadConfig } from "../src/config";

describe("Health endpoint", () => {
  it("should return ok status", async () => {
    const config = loadConfig();
    const req = new Request("http://localhost/health");
    const res = handleHealth(req, config);

    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.status).toBe("ok");
    expect(typeof body.uptime).toBe("number");
    expect(typeof body.sseClients).toBe("number");
  });
});
