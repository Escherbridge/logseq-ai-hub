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
- **Never trust host value shapes for properties.** Against a live graph,
  `getBlockProperty` returned link-valued properties as objects that `js->clj`
  leaves opaque, and `add` wrote `[[[object Object]]]` into the page. `add` and
  `remove` now read the `key:: value` line from the block's own content.
  `tags::`/`alias::` plain `a, b` text counts as a list, because Logseq treats it as one.
- **`preBlock?` is only set when Logseq parses a file.** A properties block written
  through the API is not flagged until then, so every check (page_read's `pre`,
  the "nothing before the properties block" guard) recognises it by content: the
  first top-level block, holding only `key:: value` lines.
- **A page that exists only by reference is fillable.** A `[[link]]` creates the
  target page with no content. `page_create` treats a page with no content (no
  blocks, a blank placeholder, or only a properties block) as unwritten, even under
  `if_exists: "skip"`. Linking first and writing later is the natural order when
  building a graph, and the first live corpus run tripped on exactly that.
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

## job-runner-shape

`parser/parse-job-definition` and `parse-skill-definition` return
`{:job-id|:skill-id, :properties {...}, :steps [...], :valid ...}`, but the runner
(`(:job-skill job-def)`, `(:job-status ...)`) and the bridge's list/get handlers read
properties as flat keys. Because of that mismatch every job failed with "Job missing
skill reference" and `job_get`/`skill_get` returned blanks. Other consumers expect the
nested map (`registry/scanner` reads `[:properties :skill-description]`, and
`scheduler` copes with both). `job_runner/graph.cljs` `read-job-page` and
`read-skill-page` therefore return `(merge (:properties d) d)`, which has the flat keys
and keeps `:properties`, so every consumer works. The same functions handle the two
page layouts. A step block has a `step-action::` line. Steps are the first block's step
children when it has any (a nested note does not count). Otherwise they are the later
top-level step blocks, which is how pages built with
`createPage(..., {createFirstBlock false})` + `appendBlockInPage` (as `create_skill`
writes them) end up. Runner log lines, also top-level blocks, are skipped, and
`append-job-log!` collapses `::` to `:` so a logged error can never read back as a step. `job-skill`
written as `[[Skills/x]]` is reduced to `Skills/x`. `job_flow_test.cljs` covers this end
to end on the fake host. Reverting either the merge or the layout rule turns it red.

Property values are parsed by their schema type (`parser/parse-value`). Only `:csv` keys
(`skill-inputs/outputs/tags`, `job-depends-on`, and the registry's `prompt-arguments`)
become vectors. `:string` keys stay text even when they contain commas or start with `[`.
The parser used to split any value containing a comma, so an LLM reply with a comma read
back from `job-result` as a list, and registry descriptions with commas failed validation.
Registry parsers pass their own schema as the second argument.

## job-runner-queue

`queue.cljs` is pure: `enqueue` returns a new queue and `dequeue` returns
`[entry remaining]`. The runner used to treat it as mutable. It dropped `enqueue`'s
return value and took `poll-tick!`'s `[entry q]` vector for an entry, so a queued job
never ran. The test mocks were mutable too, which is why the suite stayed green. Now
`runner-state :queue` holds the queue value and is replaced on every
enqueue/dequeue/remove. `poll-tick!` stores `remaining` and adds the job to `:running`
before its async page reads. `enqueue-entry!` ignores a job that is already queued,
because `create_job` enqueues and `start-runner!` re-enqueues every `job-status:: queued`
page. Running guard: an id in `:running` is never enqueued (`enqueue-entry!` is a no-op
and `enqueue-job!` resolves `"running"` without rewriting `job-status`) and never dequeued
(`queue/job-eligible?`), so a `start_job` that lands mid-run cannot start a second
concurrent run (`job_flow_test/start-job-during-a-run-does-not-run-it-twice`).
The queue fns default to `queue/*`, and tests use the real ones.
`job_flow_test/queued-job-runs-through-the-poll-loop` covers the path from `create_job`
to `completed` without calling `execute-job!`.

Failures stay failures. A skill with no steps rejects in `engine/execute-skill`, and the
LLM handler (`agent/make-llm-handler`) rejects. It used to resolve an "⚠️ Error…" string,
so llm-call steps completed. `/LLM` still shows that text through
`agent/failure-display-text`. `job-error` holds the error's `:message`, not a printed map.
