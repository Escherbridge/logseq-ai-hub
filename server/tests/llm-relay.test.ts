import { describe, it, expect, beforeEach, afterEach } from "bun:test";
import { Database } from "bun:sqlite";
import { createRouter } from "../src/router";
import { createTestDb, makeTestConfig } from "./helpers";
import { redactCredentials } from "../src/services/llm";
import type { Config } from "../src/config";

/**
 * Drives /api/llm/* through the real router so routing + auth + CORS are
 * exercised, stubbing only the network edge (globalThis.fetch) to stand in
 * for the org's gateway.
 */

type Captured = { url: string; init: { method?: string; headers?: Record<string, string>; body?: string } };

function stubFetch(impl: (url: string, init: any) => Promise<Response>) {
  globalThis.fetch = impl as unknown as typeof fetch;
}

describe("LLM relay", () => {
  let db: Database;
  let handler: (req: Request) => Promise<Response>;
  let config: Config;
  const realFetch = globalThis.fetch;

  beforeEach(() => {
    db = createTestDb();
    config = makeTestConfig({
      pluginApiToken: "test-token",
      llmApiKey: "server-key",
      llmEndpoint: "https://gateway.example.com/v1",
      agentModel: "default-model",
    });
    handler = createRouter({ config, db });
  });

  afterEach(() => {
    globalThis.fetch = realFetch;
  });

  it("forwards an authorized chat completion with the server's key, not the plugin token", async () => {
    let captured: Captured | null = null;
    stubFetch(async (url, init) => {
      captured = { url, init };
      return new Response(JSON.stringify({ id: "x", choices: [{ message: { content: "hi" } }] }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    });

    const res = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: "gpt-4",
          messages: [{ role: "user", content: "hi" }],
          tools: [{ type: "function", function: { name: "noop" } }],
        }),
      })
    );

    expect(res.status).toBe(200);
    expect(res.headers.get("Access-Control-Allow-Origin")).toBe("*");
    expect(await res.json()).toEqual({ id: "x", choices: [{ message: { content: "hi" } }] });

    expect(captured!.url).toBe("https://gateway.example.com/v1/chat/completions");
    expect(captured!.init.headers!["Authorization"]).toBe("Bearer server-key");
    const sent = JSON.parse(captured!.init.body!);
    expect(sent.model).toBe("gpt-4");
    expect(sent.messages).toEqual([{ role: "user", content: "hi" }]);
    expect(sent.tools).toEqual([{ type: "function", function: { name: "noop" } }]);
  });

  it("/health counts relayed requests and their upstream status, but not rejected ones", async () => {
    stubFetch(async () => new Response(JSON.stringify({ error: { message: "quota" } }), { status: 429 }));
    const relayHealth = async () => (await (await handler(new Request("http://localhost/health"))).json()).llmRelay;
    const before = await relayHealth();

    await handler(new Request("http://localhost/api/llm/models", { headers: { Authorization: "Bearer wrong" } }));
    await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ messages: [] }),
      })
    );

    const after = await relayHealth();
    expect(after.configured).toBe(true);
    expect(after.requests - before.requests).toBe(1); // the 401 never reached the relay
    expect(after.lastStatus).toBe(429);
  });

  it("strips trailing slashes from the endpoint before appending the path", async () => {
    config = makeTestConfig({
      pluginApiToken: "test-token",
      llmApiKey: "server-key",
      llmEndpoint: "https://gateway.example.com/v1///",
    });
    handler = createRouter({ config, db });

    let captured: Captured | null = null;
    stubFetch(async (url, init) => {
      captured = { url, init };
      return new Response("{}", { status: 200 });
    });

    await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ messages: [] }),
      })
    );

    expect(captured!.url).toBe("https://gateway.example.com/v1/chat/completions");
  });

  it("passes through a non-2xx upstream response verbatim", async () => {
    stubFetch(async () => new Response(JSON.stringify({ error: { message: "bad key" } }), { status: 401 }));

    const res = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ messages: [] }),
      })
    );

    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: { message: "bad key" } });
    // Upstream sent no Content-Type -> the relay defaults to application/json.
    expect(res.headers.get("Content-Type")).toBe("application/json");
  });

  it("defaults a missing or blank model to config.agentModel", async () => {
    for (const body of [{ messages: [] }, { model: "", messages: [] }, { model: "   ", messages: [] }]) {
      let captured: Captured | null = null;
      stubFetch(async (url, init) => {
        captured = { url, init };
        return new Response("{}", { status: 200 });
      });

      await handler(
        new Request("http://localhost/api/llm/chat/completions", {
          method: "POST",
          headers: { Authorization: `Bearer ${config.pluginApiToken}` },
          body: JSON.stringify(body),
        })
      );

      expect(JSON.parse(captured!.init.body!).model).toBe("default-model");
    }
  });

  it("merges LLM_EXTRA_HEADERS, overriding Authorization case-insensitively", async () => {
    config = makeTestConfig({
      pluginApiToken: "test-token",
      llmApiKey: "server-key",
      llmEndpoint: "https://gateway.example.com/v1",
      llmExtraHeaders: { authorization: "Custom x", "api-key": "k" },
    });
    handler = createRouter({ config, db });

    let captured: Captured | null = null;
    stubFetch(async (url, init) => {
      captured = { url, init };
      return new Response("{}", { status: 200 });
    });

    await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ messages: [] }),
      })
    );

    const headers = captured!.init.headers!;
    const authKeys = Object.keys(headers).filter((k) => k.toLowerCase() === "authorization");
    expect(authKeys.length).toBe(1);
    expect(headers[authKeys[0]]).toBe("Custom x");
    expect(headers["api-key"]).toBe("k");
  });

  it("proceeds (not 503) when only LLM_EXTRA_HEADERS is set and the key is empty", async () => {
    config = makeTestConfig({
      pluginApiToken: "test-token",
      llmApiKey: "",
      llmEndpoint: "https://gateway.example.com/v1",
      llmExtraHeaders: { "api-key": "k" },
    });
    handler = createRouter({ config, db });

    stubFetch(async () => new Response("{}", { status: 200 }));

    const res = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ messages: [] }),
      })
    );

    expect(res.status).toBe(200);
  });

  it("returns 503 relay_not_configured and never calls upstream when no credentials are set", async () => {
    config = makeTestConfig({
      pluginApiToken: "test-token",
      llmApiKey: "",
      llmExtraHeaders: {},
      llmEndpoint: "https://gateway.example.com/v1",
    });
    handler = createRouter({ config, db });

    let called = false;
    stubFetch(async () => {
      called = true;
      return new Response("{}", { status: 200 });
    });

    const res = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ messages: [] }),
      })
    );

    expect(res.status).toBe(503);
    const body = await res.json();
    expect(body.error.type).toBe("relay_not_configured");
    expect(called).toBe(false);
  });

  it("rejects a wrong or missing plugin token with 401 and never calls upstream", async () => {
    let called = false;
    stubFetch(async () => {
      called = true;
      return new Response("{}", { status: 200 });
    });

    const wrongAuth = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: "Bearer wrong-token" },
        body: JSON.stringify({ messages: [] }),
      })
    );
    expect(wrongAuth.status).toBe(401);

    const missingAuth = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        body: JSON.stringify({ messages: [] }),
      })
    );
    expect(missingAuth.status).toBe(401);

    expect(called).toBe(false);
  });

  it("returns 502 relay_unreachable on a network failure, naming only the origin", async () => {
    // Bun's real message for a bad header value quotes the value, i.e. the credential.
    stubFetch(async () => {
      throw new TypeError("Header 'authorization' has invalid value: 'Bearer server-key'");
    });

    const res = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ messages: [] }),
      })
    );

    expect(res.status).toBe(502);
    const body = await res.json();
    expect(body.error.type).toBe("relay_unreachable");
    expect(body.error.message).toContain("https://gateway.example.com");
    expect(body.error.message).not.toContain("server-key");
  });

  it("returns 504 relay_timeout when the upstream fetch aborts on the relay's own timeout, naming the configured seconds", async () => {
    stubFetch(async () => {
      throw new DOMException("The operation timed out.", "TimeoutError");
    });

    const res = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ messages: [] }),
      })
    );

    expect(res.status).toBe(504);
    const body = await res.json();
    expect(body.error.type).toBe("relay_timeout");
    expect(body.error.message).toContain(`${config.llmRelayTimeoutSeconds}s`);
  });

  it("uses a configured LLM_RELAY_TIMEOUT_SECONDS, not the default, in the timeout message", async () => {
    config = makeTestConfig({
      pluginApiToken: "test-token",
      llmApiKey: "server-key",
      llmEndpoint: "https://gateway.example.com/v1",
      llmRelayTimeoutSeconds: 45,
    });
    handler = createRouter({ config, db });

    stubFetch(async () => {
      throw new DOMException("The operation timed out.", "TimeoutError");
    });

    const res = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ messages: [] }),
      })
    );

    expect(res.status).toBe(504);
    const body = await res.json();
    expect(body.error.message).toContain("45s");
    expect(body.error.message).not.toContain("110s");
  });

  it("returns 503 relay_misconfigured and never calls upstream when LLM config failed to load; a wrong token still gets 401 first", async () => {
    config = makeTestConfig({
      pluginApiToken: "test-token",
      llmApiKey: "server-key",
      llmEndpoint: "https://gateway.example.com/v1",
      llmConfigErrors: ["LLM_RELAY_TOKENS entries must be at least 16 characters"],
    });
    handler = createRouter({ config, db });

    let called = false;
    stubFetch(async () => {
      called = true;
      return new Response("{}", { status: 200 });
    });

    const wrongAuth = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: "Bearer wrong-token" },
        body: JSON.stringify({ messages: [] }),
      })
    );
    expect(wrongAuth.status).toBe(401);

    const res = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ messages: [] }),
      })
    );

    expect(res.status).toBe(503);
    const body = await res.json();
    expect(body.error.type).toBe("relay_misconfigured");
    expect(body.error.message).toContain("LLM_RELAY_TOKENS entries must be at least 16 characters");
    expect(called).toBe(false);

    const models = await handler(
      new Request("http://localhost/api/llm/models", {
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
      })
    );
    expect(models.status).toBe(503);
    expect((await models.json()).error.type).toBe("relay_misconfigured");
    expect(called).toBe(false);
  });

  it("GET /api/llm/models proxies to <endpoint>/models with the same auth headers, no Content-Type", async () => {
    let captured: Captured | null = null;
    stubFetch(async (url, init) => {
      captured = { url, init };
      return new Response(JSON.stringify({ data: [] }), { status: 200, headers: { "Content-Type": "application/json" } });
    });

    const res = await handler(
      new Request("http://localhost/api/llm/models", {
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
      })
    );

    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ data: [] });
    expect(captured!.url).toBe("https://gateway.example.com/v1/models");
    expect(captured!.init.headers!["Authorization"]).toBe("Bearer server-key");
    expect(captured!.init.headers!["Content-Type"]).toBeUndefined();
  });

  it("/health reports the llmConfigErrors count but never the error messages", async () => {
    config = makeTestConfig({
      pluginApiToken: "test-token",
      llmApiKey: "server-key",
      llmEndpoint: "https://gateway.example.com/v1",
      llmConfigErrors: ["LLM_RELAY_TOKENS entries must be at least 16 characters", "LLM_TITLE contains characters not allowed in an HTTP header"],
    });
    handler = createRouter({ config, db });

    const res = await handler(new Request("http://localhost/health"));
    const body = await res.json();
    expect(body.llmRelay.errors).toBe(2);
    expect(body.llmRelay.configured).toBe(false);
    expect(JSON.stringify(body)).not.toContain("LLM_RELAY_TOKENS");
  });

  it("rejects an invalid or non-object JSON body with 400 invalid_request", async () => {
    for (const body of ["not json", "[]", "null", '"a string"']) {
      const res = await handler(
        new Request("http://localhost/api/llm/chat/completions", {
          method: "POST",
          headers: { Authorization: `Bearer ${config.pluginApiToken}` },
          body,
        })
      );
      expect(res.status).toBe(400);
      const parsed = await res.json();
      expect(parsed.error.type).toBe("invalid_request");
    }
  });

  it("a relay token unlocks only /api/llm/* — never /api/send or /mcp", async () => {
    config = makeTestConfig({
      pluginApiToken: "test-token",
      llmApiKey: "server-key",
      llmEndpoint: "https://gateway.example.com/v1",
      llmRelayTokens: ["relay-token-1234567890"],
    });
    handler = createRouter({ config, db });
    stubFetch(async () => new Response(JSON.stringify({ data: [] }), { status: 200, headers: { "Content-Type": "application/json" } }));

    const relayAuth = { Authorization: "Bearer relay-token-1234567890" };

    const modelsRes = await handler(new Request("http://localhost/api/llm/models", { headers: relayAuth }));
    expect(modelsRes.status).toBe(200);

    const sendRes = await handler(
      new Request("http://localhost/api/send", {
        method: "POST",
        headers: relayAuth,
        body: JSON.stringify({}),
      })
    );
    expect(sendRes.status).toBe(401);

    const mcpRes = await handler(
      new Request("http://localhost/mcp", {
        method: "POST",
        headers: { ...relayAuth, "Content-Type": "application/json" },
        body: "{}",
      })
    );
    expect(mcpRes.status).toBe(401);
  });

  it("forwards only allowlisted body fields, dropping LiteLLM/OpenRouter override fields", async () => {
    let captured: Captured | null = null;
    stubFetch(async (url, init) => {
      captured = { url, init };
      return new Response("{}", { status: 200 });
    });

    await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({
          model: "gpt-4",
          messages: [{ role: "user", content: "hi" }],
          tools: [{ type: "function", function: { name: "noop" } }],
          temperature: 0.5,
          api_base: "https://evil.example.com",
          base_url: "https://evil.example.com",
          api_key: "stolen-key",
          stream: true,
          n: 5,
          models: ["other-model"],
          provider: { order: ["x"] },
          user: "someone",
          extra_headers: { "x-injected": "y" },
        }),
      })
    );

    const sent = JSON.parse(captured!.init.body!);
    expect(Object.keys(sent).sort()).toEqual(["messages", "model", "temperature", "tools"]);
  });

  it("rejects a disallowed model with 400 model_not_allowed before calling upstream; allows a listed one", async () => {
    config = makeTestConfig({
      pluginApiToken: "test-token",
      llmApiKey: "server-key",
      llmEndpoint: "https://gateway.example.com/v1",
      llmAllowedModels: ["anthropic/claude-sonnet-4"],
    });
    handler = createRouter({ config, db });

    let called = false;
    stubFetch(async () => {
      called = true;
      return new Response("{}", { status: 200 });
    });

    const disallowed = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ model: "openai/gpt-4o", messages: [] }),
      })
    );
    expect(disallowed.status).toBe(400);
    const body = await disallowed.json();
    expect(body.error.type).toBe("model_not_allowed");
    expect(called).toBe(false);

    const allowed = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ model: "anthropic/claude-sonnet-4", messages: [] }),
      })
    );
    expect(allowed.status).toBe(200);
    expect(called).toBe(true);
  });

  it("rejects an oversized request body with 413 before calling upstream", async () => {
    let called = false;
    stubFetch(async () => {
      called = true;
      return new Response("{}", { status: 200 });
    });

    const res = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${config.pluginApiToken}`,
          "Content-Length": String(5 * 1024 * 1024),
        },
        body: JSON.stringify({ messages: [] }),
      })
    );

    expect(res.status).toBe(413);
    const body = await res.json();
    expect(body.error.type).toBe("payload_too_large");
    expect(called).toBe(false);
  });

  it("builds the upstream header set from config only, and strips upstream response headers other than Content-Type", async () => {
    let captured: Captured | null = null;
    stubFetch(async (url, init) => {
      captured = { url, init };
      return new Response("{}", {
        status: 200,
        headers: {
          "Content-Type": "application/json",
          "Set-Cookie": "sid=abc",
          "X-Upstream-Secret": "shh",
        },
      });
    });

    const res = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${config.pluginApiToken}`,
          Cookie: "session=client",
          "X-Api-Key": "client-key",
          "api-key": "client-key-2",
          "X-Forwarded-For": "1.2.3.4",
        },
        body: JSON.stringify({ messages: [] }),
      })
    );

    expect(Object.keys(captured!.init.headers!).sort()).toEqual(["Authorization", "Content-Type"]);
    expect(res.headers.get("Set-Cookie")).toBeNull();
    expect(res.headers.get("X-Upstream-Secret")).toBeNull();
  });

  it("redacts the server's own credential if the upstream error body echoes it", async () => {
    stubFetch(async () => new Response(JSON.stringify({ error: { message: "bad key server-key" } }), { status: 401 }));

    const res = await handler(
      new Request("http://localhost/api/llm/chat/completions", {
        method: "POST",
        headers: { Authorization: `Bearer ${config.pluginApiToken}` },
        body: JSON.stringify({ messages: [] }),
      })
    );

    expect(res.status).toBe(401);
    const text = await res.text();
    expect(text).toContain("[redacted]");
    expect(text).not.toContain("server-key");
  });

  it("redacts the credential in the server log line for a network failure", async () => {
    const originalError = console.error;
    const logs: string[] = [];
    console.error = ((...args: unknown[]) => {
      logs.push(args.map(String).join(" "));
    }) as typeof console.error;

    stubFetch(async () => {
      throw new TypeError("Header 'authorization' has invalid value: 'Bearer server-key'");
    });

    try {
      await handler(
        new Request("http://localhost/api/llm/chat/completions", {
          method: "POST",
          headers: { Authorization: `Bearer ${config.pluginApiToken}` },
          body: JSON.stringify({ messages: [] }),
        })
      );
    } finally {
      console.error = originalError;
    }

    const logged = logs.join("\n");
    expect(logged).toContain("[redacted]");
    expect(logged).not.toContain("server-key");
  });
});

describe("redactCredentials", () => {
  it("redacts the longest matching secret first, leaving no remainder of a shorter prefix", () => {
    // pluginApiToken ("abcd") sits earlier in the unsorted secret list than the 16-char relay
    // token that contains it as a prefix — without a longest-first sort, "abcd" gets replaced
    // first and leaves "EFGH12345678" exposed because the longer secret no longer matches intact.
    const config = makeTestConfig({ pluginApiToken: "abcd", llmRelayTokens: ["abcdEFGH12345678"] });
    const result = redactCredentials("key is abcdEFGH12345678 here", config);
    expect(result).toBe("key is [redacted] here");
    expect(result).not.toContain("EFGH12345678");
  });
});
