# src/routes — directory notes

## §mcp-session-lifecycle

`POST/GET/DELETE /mcp` (`mcp-transport.ts`) implement the MCP Streamable
HTTP transport. Each MCP session MUST get its own `McpServer` — the SDK's
`Protocol.connect()` throws `Already connected to a transport...` if you
`.connect()` a transport onto a server that's already connected to one.
Sharing a single server singleton across sessions (the original design)
meant only the first session after boot ever worked; every later session
(client reconnect, restart) got a 500.

- **Template vs. session servers.** `services/mcp-server.ts` keeps the
  original `createMcpServer()` singleton as a registration *template* and
  the source of truth for `/health`'s tool/resource/prompt counts
  (`getMcpStatus()` reflects on it, not on any session server). It is
  never `.connect()`-ed to a transport. `createBareMcpServer()` builds a
  fresh, unregistered instance; `registerSessionServerFactory()` /
  `createSessionServer()` let `index.ts` install the actual "fully
  registered session server" recipe (95 static tools + dynamic registry
  attachment) without `mcp-server.ts` itself knowing about tool
  registration.
- **Dynamic tool replay.** `services/mcp/dynamic-registry.ts`'s
  `DynamicRegistry` now targets a *list* of attached servers instead of
  one. `attach(server)` replays every previously-synced tool/prompt/
  resource onto a newly attached session server (via cached binder
  closures keyed by name) so a session that connects after a
  `syncFromBridge()` poll still sees the full dynamic catalogue, not just
  what existed at that server's connect time. `detach(server)` removes it
  so future syncs/notifications don't leak onto dead sessions.
- **Cleanup is re-entrant and must be idempotent.** For a single
  DELETE-driven close, the SDK invokes both the transport's
  `onsessionclosed` option callback and its `onclose` assignment
  independently, and a session's own `dispose()` (which calls
  `server.close()`) closes the same transport again, re-firing `onclose`
  synchronously from inside the first cleanup call. `mcp-transport.ts`'s
  `cleanupSession()` keys everything off `sessions.has(id)` — deleting the
  map entry *before* calling `dispose()` — so every re-entrant call after
  the first sees no entry and returns immediately instead of recursing.
