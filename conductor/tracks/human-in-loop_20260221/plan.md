# Implementation Plan: Human-in-the-Loop Approval Flow

## Overview

This plan implements a bidirectional human approval system across 6 phases. Agents (via MCP, job runner, or agent chat) can pause execution, send a question to a human via WhatsApp/Telegram, wait for the reply, and resume with the response.

The implementation follows a bottom-up approach: build the core approval store first, then layer on the MCP tool, webhook correlation, job runner step, REST API, and SSE events. Each phase builds on the previous one and ends with a verification checkpoint.

**Estimated effort:** 12-18 hours across 30 tasks.

**Key files created/modified:**

Server (Bun/TypeScript):
- `server/src/services/approval-store.ts` (new)
- `server/src/services/mcp/approval-tools.ts` (new)
- `server/src/routes/api/approvals.ts` (new)
- `server/src/routes/webhooks/whatsapp.ts` (modified)
- `server/src/routes/webhooks/telegram.ts` (modified)
- `server/src/services/mcp/index.ts` (modified)
- `server/src/router.ts` (modified)
- `server/src/index.ts` (modified)
- `server/src/types.ts` (modified - SSEEvent union)
- `server/src/types/approval.ts` (new)

Server tests:
- `server/tests/approval-store.test.ts` (new)
- `server/tests/approval-tools.test.ts` (new)
- `server/tests/approval-correlation.test.ts` (new)
- `server/tests/api-approvals.test.ts` (new)

Plugin (ClojureScript):
- `src/main/logseq_ai_hub/job_runner/executor.cljs` (modified)
- `src/test/logseq_ai_hub/job_runner/executor_test.cljs` (modified)

---

## Phase 1: Approval Types and Store Core

**Goal:** Build the in-memory approval store with Promise-based blocking, timeout cleanup, and FIFO ordering. This is the foundational data structure that all other phases depend on.

### Task 1.1: Define Approval Types

**TDD Cycle:** Write type definitions (no test needed for pure types, but validate via compilation).

**Description:** Create `server/src/types/approval.ts` with all approval-related interfaces.

**Types to define:**
- `ApprovalStatus`: `"pending" | "approved" | "timeout" | "cancelled"`
- `ApprovalRequest`: `{ id: string; contactId: string; question: string; options?: string[]; timeout: number; createdAt: string; status: ApprovalStatus; response: string | null; resolvedBy?: "webhook" | "manual" }`
- `PendingApproval`: extends `ApprovalRequest` with `resolve: (result: ApprovalResult) => void; reject: (reason: Error) => void; timer: ReturnType<typeof setTimeout>`
- `ApprovalResult`: `{ status: "approved" | "timeout" | "cancelled"; response: string | null; resolvedBy?: "webhook" | "manual" }`
- `CreateApprovalParams`: `{ contactId: string; question: string; options?: string[]; timeout?: number }`

**File:** `server/src/types/approval.ts`

**Commit:** `feat(approval): define approval type interfaces`

---

### Task 1.2: Implement ApprovalStore — create and wait

**TDD Cycle:**
- RED: Write tests for `ApprovalStore.create()` returning a Promise, verify the approval is stored, verify `getPending()` returns it.
- GREEN: Implement `ApprovalStore` class with `create(params)` that stores a `PendingApproval` in a `Map<string, PendingApproval[]>` keyed by contactId, returns a Promise.
- REFACTOR: Extract constants (default timeout, max per contact, max total).

**Test expectations:**
- `create()` returns a Promise (does not resolve immediately)
- `getPending(contactId)` returns the pending approval
- `getPendingCount(contactId)` returns 1
- Approval `id` is a UUID
- Approval `status` is `"pending"`
- Approval `createdAt` is an ISO timestamp

**Files:**
- `server/tests/approval-store.test.ts` (new)
- `server/src/services/approval-store.ts` (new)

**Commit:** `feat(approval): implement ApprovalStore create and getPending`

---

### Task 1.3: Implement ApprovalStore — resolve

**TDD Cycle:**
- RED: Write tests for `resolve(contactId, response, resolvedBy)` — resolves the oldest pending approval for that contact.
- GREEN: Implement `resolve()` that finds the oldest FIFO entry, calls its `resolve` callback, clears its timer, removes from the Map.
- REFACTOR: Ensure clean Promise resolution.

**Test expectations:**
- Resolving an approval causes the Promise returned by `create()` to resolve with `{ status: "approved", response: "yes", resolvedBy: "webhook" }`
- After resolution, `getPending(contactId)` returns empty
- Resolving with no pending approvals returns `false`
- FIFO ordering: if 2 approvals exist for same contact, the oldest is resolved first

**Files:**
- `server/tests/approval-store.test.ts` (extended)
- `server/src/services/approval-store.ts` (extended)

**Commit:** `feat(approval): implement approval resolution with FIFO ordering`

---

### Task 1.4: Implement ApprovalStore — resolve with options validation

**TDD Cycle:**
- RED: Write tests for resolving an approval that has `options` set — response must match one of the options (case-insensitive, trimmed).
- GREEN: Implement options validation in `resolve()`. Return `{ matched: false }` if the response does not match.
- REFACTOR: Extract option matching logic into a helper.

**Test expectations:**
- Approval with `options: ["approve", "reject"]` and response `"Approve"` resolves successfully (case-insensitive)
- Approval with `options: ["approve", "reject"]` and response `" approve "` resolves successfully (trimmed)
- Approval with `options: ["approve", "reject"]` and response `"maybe"` returns `{ matched: false }`
- Approval with no `options` accepts any response

**Files:**
- `server/tests/approval-store.test.ts` (extended)
- `server/src/services/approval-store.ts` (extended)

**Commit:** `feat(approval): add options validation to approval resolution`

---

### Task 1.5: Implement ApprovalStore — timeout

**TDD Cycle:**
- RED: Write tests for timeout behavior — approval auto-rejects after timeout duration.
- GREEN: In `create()`, set up a `setTimeout` that calls `reject` with a timeout error and removes the approval. Use the approval's `timeout` field (in seconds, default 300).
- REFACTOR: Ensure cleanup is deterministic.

**Test expectations:**
- Approval with `timeout: 0.1` (100ms for testing) auto-rejects after ~100ms with `{ status: "timeout", response: null }`
- After timeout, `getPending(contactId)` returns empty
- Resolving after timeout has no effect (returns false)
- Timer is cleared when approval is resolved before timeout

**Files:**
- `server/tests/approval-store.test.ts` (extended)
- `server/src/services/approval-store.ts` (extended)

**Commit:** `feat(approval): implement approval timeout with auto-rejection`

---

### Task 1.6: Implement ApprovalStore — cancel and limits

**TDD Cycle:**
- RED: Write tests for `cancel(approvalId)` — rejects the Promise with a cancellation error. Test max-per-contact (5) and max-total (100) limits.
- GREEN: Implement `cancel()` that finds the approval by ID across all contacts, calls `reject`, removes it. Implement limit checks in `create()`.
- REFACTOR: Add `getAll()` method for the REST API listing.

**Test expectations:**
- `cancel(id)` causes the Promise to reject with `{ status: "cancelled" }`
- After cancel, approval is removed from pending
- `cancel()` with unknown ID returns `false`
- Creating 6th approval for same contact throws an error
- Creating approval when 100 total are pending throws an error
- `getAll()` returns all pending approvals across all contacts

**Files:**
- `server/tests/approval-store.test.ts` (extended)
- `server/src/services/approval-store.ts` (extended)

**Commit:** `feat(approval): implement cancel, per-contact limits, and getAll`

---

### Task 1.7: Implement ApprovalStore — resolveById for manual resolution

**TDD Cycle:**
- RED: Write tests for `resolveById(approvalId, response)` — resolves a specific approval by its UUID regardless of FIFO order.
- GREEN: Implement by scanning all contact queues for the matching ID.
- REFACTOR: Share cleanup logic with `resolve()`.

**Test expectations:**
- `resolveById(id, "approved by admin")` resolves the correct approval
- The Promise resolves with `{ status: "approved", response: "approved by admin", resolvedBy: "manual" }`
- Unknown ID returns `false`

**Files:**
- `server/tests/approval-store.test.ts` (extended)
- `server/src/services/approval-store.ts` (extended)

**Commit:** `feat(approval): implement resolveById for manual admin resolution`

---

### Verification 1

- [ ] All `server/tests/approval-store.test.ts` tests pass (`cd server && bun test tests/approval-store.test.ts`)
- [ ] ApprovalStore handles create, resolve (FIFO), resolve with options, timeout, cancel, resolveById, limits
- [ ] No orphaned timers — all code paths clean up timeouts
- [ ] Promise-based blocking pattern works correctly

[checkpoint marker]

---

## Phase 2: MCP Tool — `ask_human`

**Goal:** Expose the `ask_human` tool via the MCP server. This is the primary interface for Claude Code to request human approval.

### Task 2.1: Implement contact resolution helper

**TDD Cycle:**
- RED: Write tests for a `resolveContact(db, contactIdentifier)` function that handles both `platform:id` format and display name lookup.
- GREEN: Implement the function. If input matches `platform:id` pattern, look up directly. If display name, query contacts table. Error on ambiguous matches (multiple contacts with same name).
- REFACTOR: Handle edge cases (no match, multiple matches).

**Test expectations:**
- `resolveContact(db, "whatsapp:15551234567")` returns the contact with that ID
- `resolveContact(db, "Test User")` returns the contact with that display name
- `resolveContact(db, "Unknown Person")` throws "Contact not found"
- With two contacts named "John", `resolveContact(db, "John")` throws "Multiple contacts match"
- Case-insensitive display name matching

**Files:**
- `server/tests/approval-tools.test.ts` (new)
- `server/src/services/mcp/approval-tools.ts` (new) or `server/src/db/contacts.ts` (extend)

**Commit:** `feat(approval): implement contact resolution for ask_human`

---

### Task 2.2: Implement message formatting helper

**TDD Cycle:**
- RED: Write tests for `formatApprovalMessage(question, options?, timeoutSeconds?)` that formats the outbound message.
- GREEN: Implement the formatter following the spec's message format template.
- REFACTOR: Clean formatting.

**Test expectations:**
- Basic question: returns message with question text and timeout info
- With options `["approve", "reject"]`: includes "Reply with one of: approve, reject"
- With timeout 300: includes "Please reply within 5 minutes"
- With timeout 3600: includes "Please reply within 60 minutes"
- Without options: no "Reply with" line

**Files:**
- `server/tests/approval-tools.test.ts` (extended)
- `server/src/services/mcp/approval-tools.ts` (extended)

**Commit:** `feat(approval): implement approval message formatting`

---

### Task 2.3: Register `ask_human` MCP tool

**TDD Cycle:**
- RED: Write test that `registerApprovalTools(server, getContext)` registers the `ask_human` tool with correct schema.
- GREEN: Implement `registerApprovalTools` following the pattern in `messaging-tools.ts`. The tool handler: resolves contact, formats message, sends message via internal fetch to `/api/send`, creates approval in the store, awaits the approval Promise, returns result.
- REFACTOR: Error handling for all failure modes.

**Test expectations:**
- Tool `ask_human` is registered on the MCP server
- Tool schema matches the spec (contact, question, options, timeout_seconds)
- Calling the tool with a mock bridge sends a message and creates an approval
- Tool returns `{ status: "approved", response: "yes" }` when approval is resolved
- Tool returns error content when contact not found
- Tool respects max timeout (3600 seconds cap)

**Files:**
- `server/tests/approval-tools.test.ts` (extended)
- `server/src/services/mcp/approval-tools.ts` (extended)

**Commit:** `feat(approval): register ask_human MCP tool`

---

### Task 2.4: Wire approval tools into MCP server registration

**TDD Cycle:**
- RED: Verify `registerAllMcpHandlers` includes approval tools.
- GREEN: Add `registerApprovalTools(server, getContext)` call to `server/src/services/mcp/index.ts`. Pass `approvalStore` via context.
- REFACTOR: Update `McpToolContext` to include optional `approvalStore`.

**Implementation details:**
- Add `approvalStore?: ApprovalStore` to `McpToolContext` in `server/src/types/mcp.ts`
- Create `ApprovalStore` instance in `server/src/index.ts` and pass to context factory
- Import and call `registerApprovalTools` in MCP index

**Files:**
- `server/src/services/mcp/index.ts` (modified)
- `server/src/types/mcp.ts` (modified)
- `server/src/index.ts` (modified)

**Commit:** `feat(approval): wire ask_human into MCP server and index`

---

### Verification 2

- [ ] `bun test tests/approval-tools.test.ts` passes
- [ ] `ask_human` tool is registered and shows in MCP tool listing
- [ ] End-to-end mental trace: MCP call -> contact resolved -> message sent -> approval created -> Promise blocks -> (next phase wires resolution)
- [ ] `server/src/index.ts` creates ApprovalStore and passes to MCP context

[checkpoint marker]

---

## Phase 3: Webhook-to-Approval Correlation

**Goal:** When an incoming webhook message arrives from a contact with pending approvals, automatically resolve the oldest pending approval. This closes the loop for the `ask_human` flow.

### Task 3.1: Create approval correlation service

**TDD Cycle:**
- RED: Write tests for a `checkAndResolveApproval(approvalStore, contactId, messageContent, sendFollowUp)` function.
- GREEN: Implement: check if contactId has pending approvals, attempt to resolve with message content. If options validation fails (`matched: false`), call `sendFollowUp` callback with a "please reply with one of..." message. If resolved, broadcast SSE event.
- REFACTOR: Return a result indicating whether an approval was resolved.

**Test expectations:**
- Contact with no pending approvals: returns `{ resolved: false }`
- Contact with pending approval (no options): resolves it, returns `{ resolved: true, approvalId: "..." }`
- Contact with pending approval (options match): resolves, returns `{ resolved: true }`
- Contact with pending approval (options mismatch): does NOT resolve, calls sendFollowUp with formatted message, returns `{ resolved: false, optionsMismatch: true }`
- Multiple pending approvals: resolves the oldest only

**Files:**
- `server/tests/approval-correlation.test.ts` (new)
- `server/src/services/approval-correlation.ts` (new)

**Commit:** `feat(approval): implement approval correlation service`

---

### Task 3.2: Hook correlation into WhatsApp webhook handler

**TDD Cycle:**
- RED: Write integration test: create a pending approval for a WhatsApp contact, then simulate a webhook message from that contact. Verify the approval is resolved.
- GREEN: Modify `handleWhatsAppWebhook` to call `checkAndResolveApproval` after storing the message. Pass a `sendFollowUp` callback that uses the existing send message infrastructure.
- REFACTOR: Keep the webhook handler clean by delegating to the correlation service.

**Implementation details:**
- The `approvalStore` must be accessible from the webhook handler. Add it to `RouteContext`.
- Add `approvalStore?: ApprovalStore` to `RouteContext` in `server/src/router.ts`.
- Pass `approvalStore` when creating the router in `server/src/index.ts`.
- The webhook handler calls `checkAndResolveApproval` after the `insertMessage` + `sseManager.broadcast` calls.
- For the `sendFollowUp` callback, use internal fetch to `POST /api/send` (same pattern as `messaging-tools.ts`).

**Test expectations:**
- Webhook message from contact with pending approval resolves it
- Normal message flow (SQLite storage, SSE broadcast) still happens
- Webhook message from contact without pending approval has no side effects
- Options mismatch triggers a follow-up message

**Files:**
- `server/tests/approval-correlation.test.ts` (extended)
- `server/src/routes/webhooks/whatsapp.ts` (modified)
- `server/src/router.ts` (modified - RouteContext)
- `server/src/index.ts` (modified - pass approvalStore to router)

**Commit:** `feat(approval): hook approval correlation into WhatsApp webhook`

---

### Task 3.3: Hook correlation into Telegram webhook handler

**TDD Cycle:**
- RED: Write integration test similar to 3.2 but for Telegram.
- GREEN: Modify `handleTelegramWebhook` with the same correlation call.
- REFACTOR: Ensure both webhook handlers follow the same pattern.

**Test expectations:**
- Same as Task 3.2 but for Telegram contact IDs (`telegram:userId`)

**Files:**
- `server/tests/approval-correlation.test.ts` (extended)
- `server/src/routes/webhooks/telegram.ts` (modified)

**Commit:** `feat(approval): hook approval correlation into Telegram webhook`

---

### Verification 3

- [ ] `bun test tests/approval-correlation.test.ts` passes
- [ ] Full flow test: create approval -> simulate webhook -> approval Promise resolves
- [ ] Normal message processing is not affected (messages still stored, SSE still broadcast)
- [ ] Options mismatch sends follow-up message to user
- [ ] Both WhatsApp and Telegram webhooks support correlation

[checkpoint marker]

---

## Phase 4: REST API -- Approval Management

**Goal:** Expose REST endpoints for monitoring and manually managing approvals.

### Task 4.1: Implement GET /api/approvals handler

**TDD Cycle:**
- RED: Write tests for `handleListApprovals` — returns all pending approvals.
- GREEN: Implement handler that calls `approvalStore.getAll()`, maps to public shape (without resolve/reject functions), returns JSON.
- REFACTOR: Use `successResponse` helper pattern.

**Test expectations:**
- Returns empty list when no approvals pending
- Returns list of pending approvals with correct fields (id, contactId, question, options, createdAt, timeout)
- Requires Bearer auth (401 without token)
- Does not expose internal fields (resolve/reject functions)

**Files:**
- `server/tests/api-approvals.test.ts` (new)
- `server/src/routes/api/approvals.ts` (new)

**Commit:** `feat(approval): implement GET /api/approvals endpoint`

---

### Task 4.2: Implement POST /api/approvals/:id/resolve handler

**TDD Cycle:**
- RED: Write tests for `handleResolveApproval` — manually resolves a specific approval.
- GREEN: Implement handler that calls `approvalStore.resolveById(id, body.response)`.
- REFACTOR: Error responses for unknown ID, missing body.

**Test expectations:**
- Resolving existing approval returns success
- Resolving unknown approval returns 404
- Missing `response` in body returns 400
- Requires Bearer auth
- The waiting Promise (from `create()`) resolves with `resolvedBy: "manual"`

**Files:**
- `server/tests/api-approvals.test.ts` (extended)
- `server/src/routes/api/approvals.ts` (extended)

**Commit:** `feat(approval): implement POST /api/approvals/:id/resolve endpoint`

---

### Task 4.3: Implement DELETE /api/approvals/:id handler

**TDD Cycle:**
- RED: Write tests for `handleCancelApproval` — cancels a pending approval.
- GREEN: Implement handler that calls `approvalStore.cancel(id)`.
- REFACTOR: Error handling.

**Test expectations:**
- Cancelling existing approval returns success
- The waiting Promise rejects with a cancellation error
- Cancelling unknown approval returns 404
- Requires Bearer auth

**Files:**
- `server/tests/api-approvals.test.ts` (extended)
- `server/src/routes/api/approvals.ts` (extended)

**Commit:** `feat(approval): implement DELETE /api/approvals/:id endpoint`

---

### Task 4.4: Register approval routes in router

**TDD Cycle:**
- RED: Verify routes are registered (integration test: hit the endpoints through the router).
- GREEN: Add the three approval routes to `server/src/router.ts`. Import handlers from `server/src/routes/api/approvals.ts`.
- REFACTOR: Group with comment block like other route groups.

**Routes to add:**
```
GET    /api/approvals              -> handleListApprovals
POST   /api/approvals/:id/resolve  -> handleResolveApproval
DELETE /api/approvals/:id          -> handleCancelApproval
```

**Files:**
- `server/src/router.ts` (modified)

**Commit:** `feat(approval): register approval REST API routes`

---

### Verification 4

- [ ] `bun test tests/api-approvals.test.ts` passes
- [ ] All three endpoints work through the router
- [ ] Auth is enforced on all endpoints
- [ ] Manual resolution via POST works end-to-end (approval Promise resolves)
- [ ] Cancel via DELETE works end-to-end (approval Promise rejects)

[checkpoint marker]

---

## Phase 5: SSE Event Broadcasting

**Goal:** Broadcast SSE events when approvals are created, resolved, or timed out so that the plugin and other SSE clients can react.

### Task 5.1: Extend SSEEvent type union

**TDD Cycle:**
- RED: TypeScript compilation should accept the new event types.
- GREEN: Add `"approval_created" | "approval_resolved" | "approval_timeout"` to the `SSEEvent.type` union in `server/src/types.ts`.
- REFACTOR: None needed.

**Files:**
- `server/src/types.ts` (modified)

**Commit:** `feat(approval): extend SSEEvent type with approval events`

---

### Task 5.2: Add SSE broadcasting to ApprovalStore lifecycle

**TDD Cycle:**
- RED: Write tests that verify SSE events are broadcast at the right moments. Use a spy/mock on `sseManager.broadcast`.
- GREEN: Add `sseManager.broadcast()` calls in:
  - `create()`: broadcast `approval_created` with `{ approvalId, contactId, question, timeout }`
  - `resolve()` / `resolveById()`: broadcast `approval_resolved` with `{ approvalId, contactId, response, resolvedBy }`
  - Timeout handler: broadcast `approval_timeout` with `{ approvalId, contactId }`
- REFACTOR: Extract event construction into helpers.

**Test expectations:**
- Creating an approval broadcasts `approval_created`
- Resolving an approval broadcasts `approval_resolved` with `resolvedBy: "webhook"` or `"manual"`
- Timeout broadcasts `approval_timeout`
- Cancel does NOT broadcast `approval_timeout` (it is a distinct action)

**Files:**
- `server/tests/approval-store.test.ts` (extended with SSE spy tests)
- `server/src/services/approval-store.ts` (modified)

**Commit:** `feat(approval): broadcast SSE events for approval lifecycle`

---

### Verification 5

- [ ] All approval store tests still pass with SSE broadcasting added
- [ ] SSE events contain correct data shapes
- [ ] No duplicate events (resolve only broadcasts once)
- [ ] Full `bun test` passes (all server tests)

[checkpoint marker]

---

## Phase 6: Job Runner Step -- `:ask-human`

**Goal:** Add a new `:ask-human` executor action type to the plugin-side job runner so skill steps can pause and wait for human input.

### Task 6.1: Implement `:ask-human` executor

**TDD Cycle:**
- RED: Write ClojureScript tests for the new executor. Mock the server HTTP call. Verify the step sends a request to the server's `/api/send` and then polls or waits for the approval result.
- GREEN: Implement `ask-human-executor` in `executor.cljs`. The step:
  1. Reads `contact`, `question`, `options`, `timeout` from step config (interpolated)
  2. Sends the approval question via HTTP to the server (POST to a new endpoint or the existing `/api/send` with approval creation)
  3. Waits for the result by polling `GET /api/approvals/:id` or by creating the approval via a dedicated server endpoint that blocks
  4. Returns the human's response as the step result
- REFACTOR: Use a dynamic var `*ask-human-fn*` for testability (same pattern as `*call-mcp-tool-fn*`).

**Implementation approach for the executor:**

The simplest approach: the executor calls a server-side endpoint that creates the approval, sends the message, and blocks until resolved. This is a long-polling approach.

Add a new endpoint `POST /api/approvals/ask` that:
1. Accepts `{ contactId, question, options?, timeout? }`
2. Sends the message via the existing send infrastructure
3. Creates the approval in the store
4. Awaits the approval Promise
5. Returns the result

The executor simply calls this endpoint and awaits the response.

**Test expectations:**
- Step with `:ask-human` action type is dispatched to the handler
- Step config is interpolated (contact can use `{{variable}}` syntax)
- On successful response, step result contains the human's reply
- Step result is available to subsequent steps via `{{step-N-result}}`
- On timeout, step behavior depends on config: `on-timeout: "fail"` (default) rejects the Promise, `on-timeout: "continue"` resolves with a default value

**Files:**
- `src/test/logseq_ai_hub/job_runner/executor_test.cljs` (extended)
- `src/main/logseq_ai_hub/job_runner/executor.cljs` (modified)

**Commit:** `feat(approval): implement :ask-human executor step type`

---

### Task 6.2: Implement server-side `POST /api/approvals/ask` endpoint

**TDD Cycle:**
- RED: Write tests for the combined ask endpoint that sends a message and creates a blocking approval.
- GREEN: Implement `handleAskApproval` handler. It resolves the contact, formats the message, sends it, creates the approval, and awaits the Promise. The HTTP response only returns when the approval is resolved/timed out/cancelled.
- REFACTOR: Reuse the contact resolution and message formatting from Phase 2.

**Test expectations:**
- Endpoint requires auth
- Sends the formatted message to the contact
- Creates a pending approval
- Returns `{ status: "approved", response: "yes" }` when resolved
- Returns `{ status: "timeout" }` on timeout
- Returns `{ status: "cancelled" }` on cancel (via another endpoint call)
- Validates required fields (contactId, question)

**Files:**
- `server/tests/api-approvals.test.ts` (extended)
- `server/src/routes/api/approvals.ts` (extended)
- `server/src/router.ts` (modified - add route)

**Commit:** `feat(approval): implement POST /api/approvals/ask blocking endpoint`

---

### Task 6.3: Register `:ask-human` executor and wire dynamic var

**TDD Cycle:**
- RED: Write integration test: register the executor, execute a step, verify it calls the server.
- GREEN: Add `register-executor! :ask-human ask-human-executor` at the bottom of `executor.cljs`. Add `*ask-human-fn*` dynamic var and use it in the executor for testability.
- REFACTOR: Ensure the executor is registered alongside the other 10 action types.

**Test expectations:**
- `:ask-human` is in the executor registry
- Executing the step with a mock `*ask-human-fn*` returns the expected result
- Step handles both `:on-timeout "fail"` and `:on-timeout "continue"` configurations

**Files:**
- `src/test/logseq_ai_hub/job_runner/executor_test.cljs` (extended)
- `src/main/logseq_ai_hub/job_runner/executor.cljs` (modified)

**Commit:** `feat(approval): register :ask-human executor and dynamic var`

---

### Verification 6

- [ ] `npx shadow-cljs compile test && node out/node-tests.js` passes (all CLJS tests)
- [ ] `cd server && bun test` passes (all server tests)
- [ ] `:ask-human` step works in the executor test suite
- [ ] `POST /api/approvals/ask` endpoint works as a blocking call
- [ ] Full integration mental trace: Job runner step -> HTTP to server -> message sent -> webhook reply -> approval resolved -> HTTP response -> step result
- [ ] The 11th action type (`:ask-human`) is registered alongside the existing 10

[checkpoint marker]

---

## Summary

| Phase | Tasks | Focus | Priority |
|-------|-------|-------|----------|
| 1 | 7 | Approval Store (core data structure) | P0 |
| 2 | 4 | MCP `ask_human` tool | P0 |
| 3 | 3 | Webhook correlation | P0 |
| 4 | 4 | REST API endpoints | P2 |
| 5 | 2 | SSE event broadcasting | P2 |
| 6 | 3 | Job runner `:ask-human` step | P1 |

**Total: 23 tasks across 6 phases**

### Dependency chain

```
Phase 1 (Store) --> Phase 2 (MCP Tool) --> Phase 3 (Webhook Correlation)
                                       --> Phase 4 (REST API)
                                       --> Phase 5 (SSE Events)
                --> Phase 6 (Job Runner Step, depends on Phase 1 + REST endpoint from 6.2)
```

Phases 4 and 5 can be done in parallel after Phase 1. Phase 6 depends on Phase 1 and adds a server endpoint that the plugin-side executor calls.
