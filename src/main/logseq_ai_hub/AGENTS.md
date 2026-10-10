# src/main/logseq_ai_hub — design notes

## graph-ops

`graph_ops.cljs` implements the bridge operations behind the graph MCP tools
(`graph_search`, `page_list`, `page_read`, `block_get`, `page_links`,
`page_create`, `page_delete`, `page_rename`, `block_insert`, `block_update`,
`block_delete`, `block_move`, `properties_set`, `journal_append`, `graph_query`,
plus the legacy `block_append`). The server renders their results as compact
text; see `server/src/services/mcp/graph-render.ts`. The wire contract (params
and result shapes) is the table in `docs/bridge-operations.md` § Graph Operations.

Decisions that are easy to undo by accident:

- **Search is datalog, not an API.** `@logseq/libs` has no full-text search, and
  `logseq.DB.q` runs the *simple-query DSL*, which is not text search (the old
  `graph_search` called it and was mislabelled). Search builds one
  case-insensitive regex of lookaheads, `(?i)(?=[\s\S]*?term1)(?=[\s\S]*?term2)`,
  so every term must appear in any order, and runs it with datascript's
  built-in `re-pattern` / `re-find`. Terms are regex-escaped, so `c++` is literal.
- **Strings are spliced into queries with `pr-str`, never passed as inputs.**
  Logseq's `datascript_query` runs `cljs.reader/read-string` on every *string*
  input, so a raw string input like `"foo bar"` becomes the symbol `foo`.
  `pr-str` produces a correctly escaped EDN string literal for the query text itself.
- **Insertion is one `insertBlock` per block, not `insertBatchBlock`.** That
  returns every new uuid, which agents need to chain edits, and its placement
  semantics are fixed by explicit `sibling`/`before` flags. "Last child" is done
  as "after the current last child" rather than relying on the API's
  child-position default. A page holding only Logseq's blank placeholder block
  is filled in place rather than appended after.
- **`block_update` preserves the `id::` line.** When a block has been referenced,
  Logseq writes `id:: <uuid>` into the file. Content returned to agents strips it,
  so naive round-trips would drop it and orphan every `((uuid))` reference after a
  re-index. `preserve-id-property` re-appends it.
- **Page properties live in the first block.** A page's properties block is its
  first block when Logseq marks it `preBlock?` *or* its content is only
  `key:: value` lines. The second test matters because a properties block created
  through the API may not be flagged yet, and without it each `properties_set`
  would stack another one.
- **Array property values are links.** `["A","B"]` is written as `[[A]], [[B]]`,
  which is what creates backlinks. `mode: "add"`/`"remove"` merge into the existing
  list case-insensitively. That merge is what `page_link` relies on so it never
  clobbers existing relations.
- **Deletes are all-or-nothing and de-duplicated.** Every uuid is resolved before
  anything is removed. A listed block that sits inside another listed block is
  skipped, since it goes with its ancestor anyway.
- **Validation throws synchronously.** `agent-bridge/run-operation` wraps handlers
  in a promise constructor so a throw becomes a rejection. Before that, a throw
  escaped the dispatcher, no callback was sent, and the server waited out its timeout.

### Testing

`src/test/logseq_ai_hub/fake_logseq.cljs` is an in-memory Logseq host backed by
**real datascript** (a test-only dependency in `shadow-cljs.edn`). Its
`datascriptQuery` post-processes results exactly as Logseq does (namespaces
dropped, uuids stringified), and its Editor methods return the plugin API's
shapes. Queries in `graph_ops.cljs` are therefore parsed and executed for real in
`graph_ops_test.cljs`, not matched against hand-written results. Mocks with the
wrong shape are how the namespace-stripping bug once shipped.
