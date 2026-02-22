# Phase 8: OpenClaw Interoperability - Architectural Decisions

## AD-1: Bidirectional Format Mapping

**Context**:
Need to convert between OpenClaw JSON format and Logseq skill definition format in both directions.

**Decision**:
Implement separate functions for each direction:
- `openclaw->logseq`: Import direction
- `logseq->openclaw`: Export direction

**Rationale**:
- Clear, explicit naming shows direction
- Easier to reason about transformations
- Each function can optimize for its direction
- Test coverage clearer (separate tests for each direction)

**Alternatives Considered**:
1. Single bidirectional function with mode parameter
   - Rejected: Too complex, harder to test
2. Protocol-based approach
   - Rejected: Overkill for two formats

**Status**: Implemented

---

## AD-2: Error Collection vs Fail-Fast Validation

**Context**:
When validating OpenClaw JSON, multiple errors may be present.

**Decision**:
Collect all validation errors and return them together.

**Implementation**:
```clojure
(let [errors (atom [])]
  ;; ... collect errors
  (if (empty? @errors)
    {:valid true}
    {:valid false :errors @errors}))
```

**Rationale**:
- Better user experience: see all errors at once
- Reduces frustration: don't fix one error just to see the next
- Helps with batch imports: know all issues upfront
- Only slight performance cost (still fast)

**Alternatives Considered**:
1. Fail-fast: Return on first error
   - Rejected: Poor UX, multiple fix cycles

**Status**: Implemented

---

## AD-3: Metadata Preservation in :openclaw-meta

**Context**:
OpenClaw JSON may contain fields not mapped to Logseq skill definition (version, author, metadata).

**Decision**:
Store unmapped fields in `:openclaw-meta` key during import, merge back during export.

**Implementation**:
```clojure
;; Import
:openclaw-meta (select-keys json-map [:version :author :metadata])

;; Export
(merge openclaw-base (:openclaw-meta skill-def))
```

**Rationale**:
- Lossless roundtrip: import → export preserves all data
- Users don't lose information
- Enables future extensions without breaking changes
- Clear separation: core fields vs metadata

**Alternatives Considered**:
1. Drop unmapped fields
   - Rejected: Lossy, bad UX
2. Store in top-level keys
   - Rejected: Pollutes skill definition namespace

**Status**: Implemented

---

## AD-4: Dynamic Var for Graph Dependency

**Context**:
`export-skill-from-graph!` needs to read from graph, but want testable code without coupling.

**Decision**:
Use dynamic var for dependency injection:
```clojure
(def ^:dynamic graph-read-skill-page nil)
```

**Rationale**:
- Clean separation: openclaw.cljs doesn't require graph.cljs
- Easy to test: bind to mock in tests
- Production use: bind to real function when calling
- No extra parameters needed in function signatures

**Alternatives Considered**:
1. Pass function as parameter
   - Rejected: Clutters function signature
   - Rejected: Every caller must pass it
2. Require graph.cljs directly
   - Rejected: Tight coupling, harder to test

**Tradeoffs**:
- Pro: Testability without mocking framework
- Pro: No coupling to implementation
- Con: Runtime error if not bound
- Con: Dynamic vars less idiomatic

**Status**: Implemented

---

## AD-5: Optional Field Handling with cond->

**Context**:
Many fields in OpenClaw JSON are optional (description, inputs, outputs, tags).

**Decision**:
Use `cond->` to conditionally include fields only when present:
```clojure
(cond-> {}
  (contains? json-map :description)
  (assoc :skill-description (:description json-map)))
```

**Rationale**:
- Avoids nil values in output maps
- Clear intent: field only included if present
- Clean, functional style
- Easy to extend with more fields

**Alternatives Considered**:
1. Always include with nil values
   - Rejected: Pollutes output
2. Filter nil values after construction
   - Rejected: Less efficient, less clear

**Status**: Implemented

---

## AD-6: Step Action as Keyword Internally

**Context**:
OpenClaw JSON uses strings for step actions ("llm-call"), but ClojureScript prefers keywords.

**Decision**:
Convert to keywords on import, back to strings on export:
```clojure
;; Import
:step-action (keyword (:action step))

;; Export
:action (name (:step-action step))
```

**Rationale**:
- Idiomatic ClojureScript: keywords for enums/types
- Better for pattern matching: `(case action :llm-call ...)`
- Type safety: can't accidentally pass wrong string
- Performance: keyword comparison faster than string

**Alternatives Considered**:
1. Keep as strings
   - Rejected: Not idiomatic, less type-safe
2. Use qualified keywords
   - Rejected: Overkill, adds complexity

**Status**: Implemented

---

## AD-7: Skill ID Prefix Normalization

**Context**:
Logseq uses "Skills/" prefix convention, OpenClaw uses simple names.

**Decision**:
Always normalize at boundaries:
- Import: Add "Skills/" prefix if missing
- Export: Strip "Skills/" prefix

**Implementation**:
```clojure
(defn extract-skill-id [name]
  (if (str/starts-with? name "Skills/")
    name
    (str "Skills/" name)))

(defn strip-skill-prefix [skill-id]
  (if (str/starts-with? skill-id "Skills/")
    (subs skill-id 7)
    skill-id))
```

**Rationale**:
- Allows flexible input: works with or without prefix
- Ensures consistency: internal format always has prefix
- OpenClaw interop: export uses simple names
- Idempotent: applying twice has same effect

**Alternatives Considered**:
1. Require exact format
   - Rejected: Less flexible, poor UX
2. Store both versions
   - Rejected: Redundant data

**Status**: Implemented

---

## AD-8: JSON Formatting with Pretty-Print

**Context**:
Export produces JSON strings. Should they be minified or formatted?

**Decision**:
Use pretty-printing with 2-space indent:
```clojure
(js/JSON.stringify (clj->js openclaw-map) nil 2)
```

**Rationale**:
- Human-readable: easier to inspect and debug
- Git-friendly: better diffs
- Industry standard: most JSON tools default to pretty
- Minimal cost: files still compress well

**Alternatives Considered**:
1. Minified JSON
   - Rejected: Hard to read, harder to debug
2. Configurable formatting
   - Rejected: Adds complexity, rarely needed

**Status**: Implemented

---

## AD-9: Import Error Handling Pattern

**Context**:
Import can fail at multiple points: JSON parsing, validation, conversion.

**Decision**:
Return `{:ok result}` or `{:error message}` pattern:
```clojure
(try
  (let [json-map (js->clj (js/JSON.parse json-str) :keywordize-keys true)
        validation (validate-openclaw-json json-map)]
    (if (:valid validation)
      {:ok (openclaw->logseq json-map)}
      {:error (str "Validation failed: " ...)}))
  (catch :default e
    {:error (str "Failed to parse JSON: " (.-message e))}))
```

**Rationale**:
- Explicit success/failure: no exceptions for expected errors
- Easy to check: `(if-let [error (:error result)] ...)`
- Composable: can chain with other operations
- Clear error messages: include context

**Alternatives Considered**:
1. Throw exceptions
   - Rejected: Exceptions for control flow anti-pattern
2. Return nil on error
   - Rejected: Loses error information

**Status**: Implemented

---

## AD-10: Validation Constants

**Context**:
Need to validate skill types and step actions against allowed values.

**Decision**:
Define constants at top of namespace:
```clojure
(def openclaw-required-fields #{:name :type :steps})
(def valid-skill-types #{"llm-chain" "tool-chain" "composite" "mcp-tool"})
(def valid-step-actions #{"graph-query" "llm-call" ...})
```

**Rationale**:
- Single source of truth: no magic strings
- Easy to update: change in one place
- Self-documenting: names explain purpose
- Reusable: validation and error messages use same set

**Alternatives Considered**:
1. Inline validation
   - Rejected: Duplication, hard to maintain
2. External schema file
   - Rejected: Overkill for simple validation

**Status**: Implemented

---

## Summary

All architectural decisions support the core goals:
1. **Interoperability**: Lossless roundtrip between formats
2. **Usability**: Clear errors, flexible input
3. **Maintainability**: Clear structure, testable code
4. **Idiomaticity**: ClojureScript conventions where appropriate

No conflicting decisions. All decisions align with project patterns.
