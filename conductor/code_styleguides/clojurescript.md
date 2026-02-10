# ClojureScript Code Style Guide

## Naming Conventions
- **Namespaces**: `kebab-case`, matching directory structure (e.g., `ai-hub.messaging`)
- **Functions**: `kebab-case` (e.g., `process-input`, `register-model`)
- **Predicates**: End with `?` (e.g., `valid-key?`, `connected?`)
- **Atoms/State**: Descriptive nouns (e.g., `models`, `connections`)
- **Constants**: `kebab-case` (no SCREAMING_CASE in Clojure)
- **Private functions**: Prefix with `-` or use `defn-`

## Code Organization
- One namespace per file
- Namespace sections separated by comment banners:
  ```clojure
  ;; ---------------------------------------------------------------------------
  ;; Section Name
  ;; ---------------------------------------------------------------------------
  ```
- Order within namespace: requires → constants → state → helpers → public API

## Functions
- Prefer pure functions; isolate side effects
- Use docstrings on public functions:
  ```clojure
  (defn register-model
    "Registers a model handler function under a specific ID."
    [model-id handler-fn]
    ...)
  ```
- Prefer `->` and `->>` threading macros for readability
- Use `when` instead of `(if x y nil)`

## JavaScript Interop
- Use `js/` prefix for global JS objects: `js/logseq`, `js/console`, `js/fetch`
- Use `aget` for property access on JS objects
- Use `clj->js` / `js->clj` at boundaries, not deep in logic
- Prefer `.then` chains for Promise interop (no core.async unless needed)

## State Management
- Use `atom` + `swap!` / `reset!` for mutable state
- Keep atoms at namespace level, not nested in functions
- Prefer `defonce` for state that should survive hot-reload

## Error Handling
- Use `.catch` on Promise chains
- Return user-friendly error messages from handlers
- Log detailed errors via `js/console.error`

## Testing
- Test namespace mirrors source: `ai-hub.agent` → `ai-hub.agent-test`
- Use `cljs.test/deftest` and `is` assertions
- Test pure functions directly; mock JS interop at boundaries
