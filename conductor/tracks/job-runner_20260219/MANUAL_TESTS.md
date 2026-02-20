# Manual Test Instructions: Job Runner System

This document provides comprehensive step-by-step manual testing instructions for the Logseq AI Hub Job Runner system. Follow these instructions to verify all functionality works correctly after implementation.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Phase-by-Phase Verification Tests](#2-phase-by-phase-verification-tests)
3. [Sample Test Data](#3-sample-test-data)
4. [Troubleshooting Guide](#4-troubleshooting-guide)

---

## 1. Prerequisites

### 1.1 Development Environment Setup

**Required Software:**
- Node.js (v14 or higher)
- Logseq desktop application (latest stable version)
- A text editor or IDE for viewing logs
- A terminal/command prompt

**Setting Up the Development Environment:**

1. **Clone and Install Dependencies**
   ```bash
   cd c:\Users\atooz\Documents\Escherbridge\LogseqPlugin
   yarn install
   ```

2. **Start Shadow-CLJS Watch Mode**
   ```bash
   yarn watch
   ```

   **Expected Output:**
   - `[:app] Build completed. (XXX files, XX compiled, 0 warnings, X.XXs)`
   - Server running on `http://localhost:8080`
   - Watch process continues running (do not close this terminal)

3. **Load Plugin in Logseq**
   - Open Logseq
   - Navigate to: `Settings > Advanced > Developer mode` (enable it)
   - Navigate to: `Settings > Plugins`
   - Click `Load unpacked plugin`
   - Select the directory: `c:\Users\atooz\Documents\Escherbridge\LogseqPlugin`
   - The plugin should appear as "Logseq AI Hub" with status "Loaded"

4. **Verify Plugin Loaded Successfully**
   - Open the browser console (Ctrl+Shift+I or Cmd+Opt+I)
   - Look for: `[Logseq AI Hub] Plugin loaded` or similar initialization message
   - Verify no red error messages in console
   - Type `/` in any block to verify slash commands are available

**Hot Reload Verification:**
- Make a small change to any `.cljs` file
- Save the file
- Watch the terminal for "Build completed"
- The plugin should automatically reload in Logseq (check console for reload message)
- **Note:** If hot reload doesn't work, manually reload the plugin via Settings > Plugins

### 1.2 Required Settings Configuration

Before testing the job runner, configure the following settings:

1. **Enable Job Runner**
   - Navigate to: `Settings > Plugin Settings > Logseq AI Hub`
   - Find `Job Runner Enabled` setting
   - Set to: `true`
   - **Default if not visible:** `false` (runner will not start)

2. **Configure Job Runner Parameters**
   - `Max Concurrent Jobs`: `3` (default)
   - `Poll Interval (ms)`: `5000` (default, 5 seconds)
   - `Default Timeout (ms)`: `300000` (default, 5 minutes)
   - `Job Page Prefix`: `Jobs/` (default)
   - `Skill Page Prefix`: `Skills/` (default)

3. **Configure MCP Servers (Optional, for Phase 5 tests)**
   - `MCP Servers`: `[]` (empty array by default)
   - For testing, use:
     ```json
     [
       {
         "id": "filesystem",
         "name": "Filesystem Server",
         "url": "http://localhost:3001",
         "transport": "streamable-http",
         "auth-token": ""
       }
     ]
     ```
   - **Note:** You must have an MCP server running locally for these tests to work

4. **Configure OpenAI API Key (for LLM tests)**
   - `OpenAI API Key`: Your OpenAI API key
   - **Note:** Required for any skills using `llm-call` steps

5. **Reload Plugin After Settings Change**
   - Navigate to: `Settings > Plugins`
   - Click the refresh icon next to "Logseq AI Hub"
   - Verify console shows reinitialization messages

---

## 2. Phase-by-Phase Verification Tests

### Phase 1: Pure Utilities (Unit Tests)

**Goal:** Verify all pure functions work correctly via automated tests.

**Test Procedure:**

1. **Run Unit Test Suite**
   ```bash
   yarn test
   ```

2. **Expected Output:**
   ```
   Testing logseq-ai-hub.job-runner.schemas-test
   Testing logseq-ai-hub.job-runner.parser-test
   Testing logseq-ai-hub.job-runner.interpolation-test
   Testing logseq-ai-hub.job-runner.cron-test
   Testing logseq-ai-hub.job-runner.queue-test

   Ran X tests containing Y assertions.
   0 failures, 0 errors.
   ```

3. **What to Check:**
   - ✅ All test namespaces load without errors
   - ✅ All tests pass (0 failures, 0 errors)
   - ✅ No warnings about undefined vars or missing dependencies
   - ✅ Test execution completes in under 10 seconds

4. **If Tests Fail:**
   - Read the failure message carefully
   - Check which test failed and which assertion
   - Review the implementation file for that module
   - Check for typos, logic errors, or missing edge case handling
   - Re-run tests after fixes

---

### Phase 2: Graph Integration

**Goal:** Verify that the job runner can read from and write to the Logseq graph.

#### Test 2.1: Read Job Page

**Setup:**
1. Create a new page in Logseq: `Jobs/test-read`
2. Add the following content to the first block:
   ```
   job-type:: manual
   job-status:: draft
   job-priority:: 3
   job-skill:: Skills/echo-test
   job-input:: {"message": "Hello from test"}
   job-created-at:: 2026-02-19T10:00:00Z
   ```

**Test Procedure:**
1. Open browser console
2. Run the following command in the console:
   ```javascript
   await logseq_ai_hub.job_runner.graph.read_job_page("Jobs/test-read")
   ```

**Expected Output:**
```javascript
{
  job-id: "Jobs/test-read",
  properties: {
    job-type: "manual",
    job-status: "draft",
    job-priority: 3,
    job-skill: "Skills/echo-test",
    job-input: {message: "Hello from test"},
    job-created-at: "2026-02-19T10:00:00Z"
  },
  valid: true
}
```

**Verification Checklist:**
- ✅ Page is read successfully (no errors)
- ✅ Properties are parsed correctly
- ✅ JSON string (`job-input`) is deserialized into an object
- ✅ Priority is converted to a number

#### Test 2.2: Read Skill Page

**Setup:**
1. Create a new page: `Skills/echo-test`
2. Add the following content to the first block:
   ```
   skill-type:: llm-chain
   skill-version:: 1
   skill-description:: Simple echo skill for testing
   skill-inputs:: message
   skill-outputs:: result
   ```
3. Add a child block under the first block:
   ```
   step-order:: 1
   step-action:: llm-call
   step-prompt-template:: Echo this message: {{message}}
   step-model:: openai-model
   ```

**Test Procedure:**
1. Open browser console
2. Run:
   ```javascript
   await logseq_ai_hub.job_runner.graph.read_skill_page("Skills/echo-test")
   ```

**Expected Output:**
```javascript
{
  skill-id: "Skills/echo-test",
  properties: {
    skill-type: "llm-chain",
    skill-version: 1,
    skill-description: "Simple echo skill for testing",
    skill-inputs: ["message"],
    skill-outputs: ["result"]
  },
  steps: [
    {
      step-order: 1,
      step-action: "llm-call",
      step-prompt-template: "Echo this message: {{message}}",
      step-model: "openai-model"
    }
  ],
  valid: true
}
```

**Verification Checklist:**
- ✅ Skill page is read successfully
- ✅ Metadata properties are parsed
- ✅ Comma-separated inputs/outputs become arrays
- ✅ Child blocks are parsed as steps in order
- ✅ Multi-line prompt template is preserved

#### Test 2.3: Write Job Status Update

**Setup:**
1. Use the existing `Jobs/test-read` page from Test 2.1

**Test Procedure:**
1. Open browser console
2. Run:
   ```javascript
   await logseq_ai_hub.job_runner.graph.update_job_status("Jobs/test-read", "queued")
   ```
3. Navigate to the `Jobs/test-read` page in Logseq

**Expected Result:**
- The first block's `job-status::` property should now read `queued`
- The change should be visible immediately in the UI

**Verification Checklist:**
- ✅ Status update succeeds (no errors in console)
- ✅ Property is updated in the graph
- ✅ Change is visible in Logseq UI

#### Test 2.4: Append Job Log Entry

**Test Procedure:**
1. Run in console:
   ```javascript
   await logseq_ai_hub.job_runner.graph.append_job_log("Jobs/test-read", "Test log entry at " + new Date().toISOString())
   ```
2. Navigate to the `Jobs/test-read` page

**Expected Result:**
- A new child block appears under the job definition
- The child block contains the log entry text with timestamp

**Verification Checklist:**
- ✅ Log entry is appended successfully
- ✅ Child block is created (not replacing existing content)
- ✅ Multiple log entries can be appended sequentially

---

### Phase 3: Step Executors

**Goal:** Test each step action type executor individually.

#### Test 3.1: `graph-query` Step

**Setup:**
1. Create some test data in your graph (any journal entries or pages)

**Test Procedure:**
1. Create a skill page: `Skills/test-graph-query`
2. Add to first block:
   ```
   skill-type:: tool-chain
   skill-version:: 1
   skill-description:: Test graph query
   skill-inputs::
   skill-outputs:: result
   ```
3. Add child block:
   ```
   step-order:: 1
   step-action:: graph-query
   step-config:: {"query": "[:find ?b :where [?b :block/content]]", "limit": 5}
   ```
4. Create a job: `Jobs/test-graph-query`
5. Add to first block:
   ```
   job-type:: manual
   job-status:: queued
   job-priority:: 3
   job-skill:: Skills/test-graph-query
   job-created-at:: 2026-02-19T11:00:00Z
   ```
6. In console, run:
   ```javascript
   await logseq_ai_hub.job_runner.runner.enqueue_job("Jobs/test-graph-query")
   ```

**Expected Result:**
- Job status changes to `running`, then `completed`
- A child block under the job page shows: `Step 1: Query returned X results`
- The `job-result` property contains the query results (first 5 blocks)

**Verification Checklist:**
- ✅ Datalog query executes successfully
- ✅ Results are returned and logged
- ✅ Limit is respected (max 5 results)

#### Test 3.2: `llm-call` Step

**Setup:**
1. Ensure OpenAI API key is configured in settings

**Test Procedure:**
1. Create skill: `Skills/test-llm-call`
2. Add to first block:
   ```
   skill-type:: llm-chain
   skill-version:: 1
   skill-description:: Test LLM call
   skill-inputs:: prompt
   skill-outputs:: response
   ```
3. Add child block:
   ```
   step-order:: 1
   step-action:: llm-call
   step-prompt-template:: {{prompt}}
   step-model:: openai-model
   ```
4. Create job: `Jobs/test-llm-call`
5. Add to first block:
   ```
   job-type:: manual
   job-status:: queued
   job-priority:: 3
   job-skill:: Skills/test-llm-call
   job-input:: {"prompt": "Say 'test successful' and nothing else"}
   job-created-at:: 2026-02-19T11:00:00Z
   ```
6. Enqueue the job

**Expected Result:**
- Job executes successfully
- Console shows API request being made
- Job result contains LLM response (should include "test successful")
- A log entry shows: `Step 1: LLM call completed`

**Verification Checklist:**
- ✅ Prompt template is interpolated correctly
- ✅ API call is made to OpenAI
- ✅ Response is captured and stored
- ✅ Job completes with status `completed`

#### Test 3.3: `block-insert` Step

**Test Procedure:**
1. Create skill: `Skills/test-block-insert`
2. Add to first block:
   ```
   skill-type:: tool-chain
   skill-version:: 1
   skill-description:: Test block insertion
   skill-inputs:: content
   skill-outputs:: block-ref
   ```
3. Add child block:
   ```
   step-order:: 1
   step-action:: block-insert
   step-config:: {"target-page": "Test Results", "content": "{{content}} - inserted at {{now}}"}
   ```
4. Create job: `Jobs/test-block-insert`
5. Add to first block:
   ```
   job-type:: manual
   job-status:: queued
   job-priority:: 3
   job-skill:: Skills/test-block-insert
   job-input:: {"content": "This is a test block"}
   job-created-at:: 2026-02-19T11:00:00Z
   ```
6. Enqueue the job

**Expected Result:**
- A new page `Test Results` is created (if it doesn't exist)
- A block with content `"This is a test block - inserted at <timestamp>"` appears on the page
- Job completes successfully

**Verification Checklist:**
- ✅ Target page is created or found
- ✅ Block is inserted with interpolated content
- ✅ Template variables ({{content}}, {{now}}) are replaced correctly
- ✅ Block reference is returned in job result

#### Test 3.4: `block-update` Step

**Setup:**
1. Create a page `Update Test Page` with a block containing: `Original content`
2. Note the block's UUID (right-click block > Copy block ref, extract UUID)

**Test Procedure:**
1. Create skill: `Skills/test-block-update`
2. Add child block:
   ```
   step-order:: 1
   step-action:: block-update
   step-config:: {"block-uuid": "PASTE-BLOCK-UUID-HERE", "content": "Updated content at {{now}}"}
   ```
3. Create and enqueue a job for this skill

**Expected Result:**
- The target block's content changes from "Original content" to "Updated content at <timestamp>"
- Job completes successfully

**Verification Checklist:**
- ✅ Correct block is found by UUID
- ✅ Block content is updated
- ✅ Timestamp is interpolated correctly

#### Test 3.5: `page-create` Step

**Test Procedure:**
1. Create skill: `Skills/test-page-create`
2. Add child block:
   ```
   step-order:: 1
   step-action:: page-create
   step-config:: {"page-name": "Auto-Generated Page {{now}}", "content": "This page was created automatically"}
   ```
3. Create and enqueue a job for this skill

**Expected Result:**
- A new page is created with a timestamped name
- The page contains the specified initial content
- Job completes successfully

**Verification Checklist:**
- ✅ New page is created
- ✅ Page name includes interpolated timestamp
- ✅ Initial content is set correctly

#### Test 3.6: `transform` Step

**Test Procedure:**
1. Create skill: `Skills/test-transform`
2. Add two child blocks:
   ```
   step-order:: 1
   step-action:: graph-query
   step-config:: {"query": "[:find ?content :where [?b :block/content ?content]]", "limit": 3}
   ```
   ```
   step-order:: 2
   step-action:: transform
   step-config:: {"op": "count", "input": "step-1-result"}
   ```
3. Create and enqueue a job for this skill

**Expected Result:**
- Step 1 executes a query returning ~3 results
- Step 2 transforms the result by counting items
- Job result shows: `3` (the count)
- Log shows: `Step 2: Transform operation 'count' completed`

**Verification Checklist:**
- ✅ Transform receives the previous step's result
- ✅ Count operation works correctly
- ✅ Result is available to subsequent steps

**Additional Transform Tests:**
- Test `get-in` operation: `{"op": "get-in", "path": ["key", "nested"], "input": "step-1-result"}`
- Test `join` operation: `{"op": "join", "separator": ", ", "input": "step-1-result"}`
- Test `split` operation: `{"op": "split", "separator": " ", "input": "step-1-result"}`

#### Test 3.7: `conditional` Step

**Test Procedure:**
1. Create skill: `Skills/test-conditional`
2. Add three child blocks:
   ```
   step-order:: 1
   step-action:: graph-query
   step-config:: {"query": "[:find ?content :where [?b :block/content ?content]]", "limit": 3}
   ```
   ```
   step-order:: 2
   step-action:: conditional
   step-config:: {"condition": "not-empty", "input": "step-1-result", "then-step": 3, "else-step": 4}
   ```
   ```
   step-order:: 3
   step-action:: block-insert
   step-config:: {"target-page": "Conditional Results", "content": "Query returned results"}
   ```
   ```
   step-order:: 4
   step-action:: block-insert
   step-config:: {"target-page": "Conditional Results", "content": "Query returned no results"}
   ```
3. Create and enqueue a job for this skill

**Expected Result:**
- Step 1 executes query (should return results)
- Step 2 evaluates condition (not-empty should be true)
- Step 3 executes (block inserted: "Query returned results")
- Step 4 is skipped
- Check page `Conditional Results` for the inserted block

**Verification Checklist:**
- ✅ Condition is evaluated correctly
- ✅ Then-branch (step 3) executes
- ✅ Else-branch (step 4) is skipped
- ✅ Execution continues after conditional

**Additional Conditional Tests:**
- Test `empty` condition with empty input
- Test `equals` condition: `{"condition": "equals", "input": "step-1-result", "value": "expected"}`
- Test `contains` condition: `{"condition": "contains", "input": "step-1-result", "value": "substring"}`

#### Test 3.8: `sub-skill` Step

**Test Procedure:**
1. Ensure `Skills/echo-test` exists from earlier tests
2. Create skill: `Skills/test-sub-skill`
3. Add child block:
   ```
   step-order:: 1
   step-action:: sub-skill
   step-config:: {"skill": "Skills/echo-test", "inputs": {"message": "Hello from sub-skill"}}
   ```
4. Create and enqueue a job for this skill

**Expected Result:**
- Step 1 loads and executes `Skills/echo-test`
- Sub-skill receives input `{"message": "Hello from sub-skill"}`
- Sub-skill executes its LLM call step
- Sub-skill result is returned to parent
- Parent job completes with sub-skill's result

**Verification Checklist:**
- ✅ Sub-skill is loaded correctly
- ✅ Inputs are passed to sub-skill
- ✅ Sub-skill executes fully
- ✅ Sub-skill result is available in parent context
- ✅ Parent can reference sub-skill result in subsequent steps

---

### Phase 4: Skill Execution Engine

**Goal:** Test the complete skill execution engine with multi-step skills, context threading, and retry logic.

#### Test 4.1: Multi-Step Skill Sequential Execution

**Test Procedure:**
1. Create skill: `Skills/multi-step-test`
2. Add to first block:
   ```
   skill-type:: llm-chain
   skill-version:: 1
   skill-description:: Multi-step skill
   skill-inputs:: topic
   skill-outputs:: summary
   ```
3. Add three child blocks:
   ```
   step-order:: 1
   step-action:: graph-query
   step-config:: {"query": "[:find ?content :where [?b :block/content ?content]]", "limit": 5}
   ```
   ```
   step-order:: 2
   step-action:: transform
   step-config:: {"op": "count", "input": "step-1-result"}
   ```
   ```
   step-order:: 3
   step-action:: block-insert
   step-config:: {"target-page": "Multi-Step Results", "content": "Found {{step-2-result}} blocks about {{topic}}"}
   ```
4. Create job: `Jobs/test-multi-step`
5. Add to first block:
   ```
   job-type:: manual
   job-status:: queued
   job-skill:: Skills/multi-step-test
   job-input:: {"topic": "testing"}
   job-created-at:: 2026-02-19T12:00:00Z
   ```
6. Enqueue the job

**Expected Result:**
- Step 1 executes: query returns 5 blocks
- Step 2 executes: count returns 5
- Step 3 executes: block inserted with "Found 5 blocks about testing"
- Job log shows all three steps completed
- Job result contains the final step's output

**Verification Checklist:**
- ✅ All steps execute in order (1, 2, 3)
- ✅ Context accumulates results (step-2-result available in step 3)
- ✅ Template interpolation works across steps
- ✅ Job completes with all steps logged

#### Test 4.2: Skill Execution with Retry Logic

**Test Procedure:**
1. Create skill: `Skills/retry-test`
2. Add child block (intentionally referencing a non-existent page to cause failure on first try):
   ```
   step-order:: 1
   step-action:: graph-query
   step-config:: {"query": "[:find ?e :where [?e :block/uuid #uuid \"00000000-0000-0000-0000-000000000000\"]]"}
   ```
3. Create job: `Jobs/test-retry`
4. Add to first block:
   ```
   job-type:: manual
   job-status:: queued
   job-skill:: Skills/retry-test
   job-max-retries:: 2
   job-retry-count:: 0
   job-created-at:: 2026-02-19T12:00:00Z
   ```
5. Enqueue the job

**Expected Result (if query fails):**
- Step 1 executes and fails (no matching UUID)
- Job status changes to `failed`
- `job-retry-count` increments to 1
- Job status changes back to `queued` (automatically)
- Job re-executes (retry attempt 1)
- If still failing, repeats up to max retries
- After max retries, status remains `failed`
- `job-error` property contains error message

**Verification Checklist:**
- ✅ Retry count increments on failure
- ✅ Job is automatically re-queued if retries remain
- ✅ After max retries, job stays in `failed` status
- ✅ Error message is written to `job-error` property
- ✅ Execution log shows all retry attempts

**Note:** To test successful retry, you would need to create a skill that fails once but succeeds on retry (e.g., using a conditional that checks retry count). This is complex to set up manually, so verify the retry logic primarily through the failure case above and unit tests.

#### Test 4.3: Execution Progress Logging

**Test Procedure:**
1. Use the `Skills/multi-step-test` from Test 4.1
2. Create a new job referencing it
3. Enqueue the job
4. Immediately navigate to the job page in Logseq

**Expected Result:**
- As the job executes, child blocks appear under the job definition in real-time
- Each child block represents a step completion:
  - `Step 1: Query returned X results`
  - `Step 2: Transform operation completed`
  - `Step 3: Block inserted successfully`
- Final log entry shows total execution time

**Verification Checklist:**
- ✅ Log entries appear in real-time
- ✅ Each step logs its completion
- ✅ Logs are human-readable and informative
- ✅ Execution duration is recorded

---

### Phase 5: MCP Client

**Goal:** Test MCP (Model Context Protocol) client connectivity and operations.

**Prerequisites:**
- You need a local MCP server running for these tests
- The simplest test server is the Anthropic filesystem server

#### Test 5.0: Set Up Local MCP Server

**Using the Anthropic Filesystem MCP Server:**

1. **Install the MCP Server**
   ```bash
   npm install -g @modelcontextprotocol/server-filesystem
   ```

2. **Start the Server**
   ```bash
   npx @modelcontextprotocol/server-filesystem --port 3001 --path c:\Users\atooz\Documents
   ```

3. **Verify Server is Running**
   - Open browser to `http://localhost:3001`
   - Should see MCP server status page or JSON-RPC endpoint

4. **Configure in Plugin Settings**
   - Go to Logseq Settings > Plugin Settings > Logseq AI Hub
   - Set `MCP Servers` to:
     ```json
     [
       {
         "id": "filesystem",
         "name": "Filesystem Server",
         "url": "http://localhost:3001",
         "transport": "streamable-http",
         "auth-token": ""
       }
     ]
     ```
   - Reload the plugin

#### Test 5.1: List MCP Servers

**Test Procedure:**
1. Open any page in Logseq
2. Type `/job:mcp-servers` and select the command
3. A block should be inserted with MCP server status

**Expected Result:**
```
MCP Servers:
- filesystem (Filesystem Server): connected
  - URL: http://localhost:3001
  - Transport: streamable-http
  - Capabilities: tools, resources
```

**Verification Checklist:**
- ✅ Command executes without errors
- ✅ Server status shows "connected"
- ✅ Server capabilities are listed
- ✅ If server is down, status shows "disconnected" or "error"

#### Test 5.2: List MCP Tools

**Test Procedure:**
1. Type `/job:mcp-tools filesystem` and select the command
2. A block should be inserted listing available tools

**Expected Result:**
```
MCP Tools on 'filesystem' server:
- read_file
  - Description: Read the complete contents of a file
  - Inputs: path (string)
- write_file
  - Description: Write content to a file
  - Inputs: path (string), content (string)
- list_directory
  - Description: List contents of a directory
  - Inputs: path (string)
```

**Verification Checklist:**
- ✅ Tools are listed with names
- ✅ Descriptions are shown
- ✅ Input schemas are displayed
- ✅ If server is disconnected, shows error message

#### Test 5.3: Call MCP Tool via Skill

**Test Procedure:**
1. Create skill: `Skills/test-mcp-tool`
2. Add to first block:
   ```
   skill-type:: mcp-tool
   skill-version:: 1
   skill-description:: Test MCP tool call
   skill-inputs:: file-path
   skill-outputs:: file-content
   ```
3. Add child block:
   ```
   step-order:: 1
   step-action:: mcp-tool
   step-mcp-server:: filesystem
   step-mcp-tool:: read_file
   step-config:: {"path": "{{file-path}}"}
   ```
4. Create job: `Jobs/test-mcp-tool`
5. Add to first block:
   ```
   job-type:: manual
   job-status:: queued
   job-skill:: Skills/test-mcp-tool
   job-input:: {"file-path": "c:\\Users\\atooz\\Documents\\test.txt"}
   job-created-at:: 2026-02-19T13:00:00Z
   ```
6. Create the test file `c:\Users\atooz\Documents\test.txt` with content: `MCP test successful`
7. Enqueue the job

**Expected Result:**
- Job executes successfully
- Step 1 calls the `read_file` tool on the MCP server
- Job result contains the file content: `"MCP test successful"`
- Job log shows: `Step 1: MCP tool 'read_file' called successfully`

**Verification Checklist:**
- ✅ MCP server connection is established
- ✅ Tool is called with correct arguments
- ✅ Tool result is returned
- ✅ Job completes successfully
- ✅ File content is accessible in job result

#### Test 5.4: MCP Resource Read

**Test Procedure:**
1. Create skill: `Skills/test-mcp-resource`
2. Add child block:
   ```
   step-order:: 1
   step-action:: mcp-resource
   step-mcp-server:: filesystem
   step-config:: {"uri": "file://c:/Users/atooz/Documents/test.txt"}
   ```
3. Create and enqueue a job for this skill

**Expected Result:**
- Resource is read via MCP
- Content is returned with MIME type
- Job completes successfully

**Verification Checklist:**
- ✅ Resource URI is resolved
- ✅ Content is fetched
- ✅ MIME type metadata is included
- ✅ Result is accessible in job

#### Test 5.5: MCP Auto-Reconnect

**Test Procedure:**
1. Ensure MCP server is connected (check with `/job:mcp-servers`)
2. Stop the MCP server (Ctrl+C in the terminal where it's running)
3. Wait 5 seconds
4. Check console for reconnection attempts
5. Restart the MCP server
6. Wait 15 seconds
7. Check `/job:mcp-servers` again

**Expected Result:**
- After server stops, status changes to "disconnected" or "error"
- Console shows reconnection attempt messages (after 1s, 5s, 15s)
- After server restarts, connection is re-established
- Status changes back to "connected"

**Verification Checklist:**
- ✅ Disconnection is detected
- ✅ Auto-reconnect attempts occur (3 retries)
- ✅ Exponential backoff is applied (1s, 5s, 15s)
- ✅ Connection is restored when server is back
- ✅ After 3 failed retries, reconnection stops (manual intervention needed)

---

### Phase 6: Job Runner Core

**Goal:** Test the central job runner orchestration, queue management, and lifecycle.

#### Test 6.1: Start and Stop Runner

**Test Procedure:**
1. Open browser console
2. Check runner status:
   ```javascript
   logseq_ai_hub.job_runner.runner.runner_status()
   ```
3. Should show: `{status: "running", queued: 0, running: 0, completed: 0, failed: 0}`
4. Stop the runner:
   ```javascript
   await logseq_ai_hub.job_runner.runner.stop_runner()
   ```
5. Check status again (should show `status: "stopped"`)
6. Start the runner:
   ```javascript
   await logseq_ai_hub.job_runner.runner.start_runner()
   ```
7. Check status again (should show `status: "running"`)

**Verification Checklist:**
- ✅ Runner starts on plugin load (if enabled in settings)
- ✅ Status reflects current runner state
- ✅ Stop command halts polling loop
- ✅ Start command resumes polling
- ✅ Console shows runner lifecycle messages

#### Test 6.2: Job Priority Ordering

**Test Procedure:**
1. Create three jobs with different priorities:
   - `Jobs/low-priority`: `job-priority:: 5`
   - `Jobs/medium-priority`: `job-priority:: 3`
   - `Jobs/high-priority`: `job-priority:: 1`
2. All jobs should reference a simple skill (e.g., `Skills/echo-test`)
3. Set all to `job-status:: queued` at the same time
4. Check `/job:status` command

**Expected Result:**
- Jobs are listed in priority order:
  1. `Jobs/high-priority` (priority 1)
  2. `Jobs/medium-priority` (priority 3)
  3. `Jobs/low-priority` (priority 5)
- Jobs execute in priority order (high priority first)
- Console logs show execution order

**Verification Checklist:**
- ✅ Queue is sorted by priority (ascending)
- ✅ Lower number = higher priority
- ✅ Jobs of same priority execute in FIFO order (by created-at timestamp)

#### Test 6.3: Concurrency Limit

**Test Procedure:**
1. Set `Max Concurrent Jobs` to `2` in settings
2. Create 5 jobs, all with same priority and simple skills
3. Set all to `job-status:: queued` simultaneously
4. Observe the runner

**Expected Result:**
- Only 2 jobs execute at once
- When one completes, the next queued job starts
- At no point are more than 2 jobs in `running` status simultaneously

**Verification Checklist:**
- ✅ Concurrency limit is respected
- ✅ New jobs start as running jobs complete
- ✅ Queue is processed until empty
- ✅ Console shows max 2 jobs running concurrently

#### Test 6.4: Job Dependency Resolution

**Test Procedure:**
1. Create three jobs:
   - `Jobs/dependency-A`: No dependencies, simple skill
   - `Jobs/dependency-B`: `job-depends-on:: [[Jobs/dependency-A]]`
   - `Jobs/dependency-C`: `job-depends-on:: [[Jobs/dependency-A]], [[Jobs/dependency-B]]`
2. Set all to `job-status:: queued` at the same time
3. Observe execution order

**Expected Result:**
- `Jobs/dependency-A` executes first
- `Jobs/dependency-B` waits until A completes, then executes
- `Jobs/dependency-C` waits until both A and B complete, then executes
- Execution order: A → B → C

**Verification Checklist:**
- ✅ Dependencies are parsed correctly (comma-separated page refs)
- ✅ Jobs wait for dependencies to complete
- ✅ Jobs with unmet dependencies are skipped in dequeue
- ✅ Once dependencies are met, job proceeds
- ✅ Circular dependencies are detected and logged as warnings

#### Test 6.5: Job Cancellation

**Test Procedure:**
1. Create a job with a long-running skill (e.g., an LLM call with a long prompt)
2. Enqueue the job
3. While the job is running, execute:
   ```javascript
   await logseq_ai_hub.job_runner.runner.cancel_job("Jobs/long-running-job")
   ```
4. Check the job page

**Expected Result:**
- Job status changes to `cancelled`
- Job is removed from queue/running set
- Execution halts (if possible, depending on step executor)
- Job log shows cancellation entry

**Verification Checklist:**
- ✅ Running job can be cancelled
- ✅ Queued job can be cancelled
- ✅ Status updates to `cancelled`
- ✅ Job does not resume execution

#### Test 6.6: Job Pause and Resume

**Test Procedure:**
1. Create a job and set to `job-status:: queued`
2. Before it executes, run:
   ```javascript
   await logseq_ai_hub.job_runner.runner.pause_job("Jobs/test-pause")
   ```
3. Verify status changes to `paused`
4. Resume:
   ```javascript
   await logseq_ai_hub.job_runner.runner.resume_job("Jobs/test-pause")
   ```
5. Verify status changes to `queued` and job executes

**Verification Checklist:**
- ✅ Paused job is removed from queue
- ✅ Paused job does not execute
- ✅ Resume re-queues the job
- ✅ Resumed job executes normally

#### Test 6.7: `/job:status` Slash Command

**Test Procedure:**
1. Create several jobs in various states (queued, running, completed, failed)
2. In any block, type `/job:status`
3. Select the command

**Expected Result:**
A block is inserted with content like:
```
Job Runner Status:
- Status: running
- Queued: 2 jobs (Jobs/test-A, Jobs/test-B)
- Running: 1 job (Jobs/test-C)
- Completed: 3 jobs (Jobs/test-D, Jobs/test-E, Jobs/test-F)
- Failed: 1 job (Jobs/test-G)
```

**Verification Checklist:**
- ✅ Command executes without errors
- ✅ Counts are accurate
- ✅ Job names are listed
- ✅ Runner status is shown

---

### Phase 7: Scheduling

**Goal:** Test cron-based job scheduling and recurring job instances.

#### Test 7.1: Create and Schedule a Recurring Job

**Test Procedure:**
1. Create a scheduled job: `Jobs/scheduled-every-minute`
2. Add to first block:
   ```
   job-type:: scheduled
   job-status:: draft
   job-schedule:: * * * * *
   job-skill:: Skills/echo-test
   job-input:: {"message": "Scheduled run"}
   job-created-at:: 2026-02-19T14:00:00Z
   ```
3. Ensure runner is started
4. Wait 2 minutes
5. Search for pages starting with `Jobs/scheduled-every-minute-`

**Expected Result:**
- Within 2 minutes, a new job instance page is created
- Instance page name: `Jobs/scheduled-every-minute-2026-02-19T14:01:00Z` (or similar timestamp)
- Instance has `job-status:: queued` and same properties as template
- Instance executes automatically
- Each minute, a new instance is created

**Verification Checklist:**
- ✅ Scheduled job is registered on runner start
- ✅ Cron expression is parsed correctly
- ✅ Job instance is created at the correct time
- ✅ Instance inherits properties from template
- ✅ Instance is queued and executed automatically
- ✅ Multiple instances can be created (every minute)

#### Test 7.2: Prevent Duplicate Scheduled Instances

**Test Procedure:**
1. Create a scheduled job with a long-running skill (e.g., waits 90 seconds)
2. Set schedule to every minute (`* * * * *`)
3. Let the scheduler fire twice while the first instance is still running

**Expected Result:**
- First instance is created at minute 1
- First instance starts running
- At minute 2, scheduler checks and sees previous instance still running
- Second instance is NOT created (skipped to prevent duplicates)
- Console logs: "Skipping scheduled job instance creation - previous instance still running"

**Verification Checklist:**
- ✅ Scheduler detects running instances
- ✅ Duplicate instances are not created
- ✅ Skipped instances are logged in console

#### Test 7.3: Various Cron Expressions

**Test different cron schedules:**

1. **Every 5 minutes:**
   ```
   job-schedule:: */5 * * * *
   ```
   - Verify instances are created at :00, :05, :10, :15, etc.

2. **Specific time (e.g., 3:00 PM daily):**
   ```
   job-schedule:: 0 15 * * *
   ```
   - Verify instance is created at 3:00 PM (requires waiting or manually testing with current time)

3. **Weekdays only (Mon-Fri at 9 AM):**
   ```
   job-schedule:: 0 9 * * 1-5
   ```
   - Verify instances are created only on weekdays

4. **Multiple specific times (e.g., 9 AM, 12 PM, 5 PM):**
   ```
   job-schedule:: 0 9,12,17 * * *
   ```
   - Verify instances are created at each specified hour

**Verification Checklist:**
- ✅ Wildcard (`*`) works
- ✅ Step values (`*/5`) work
- ✅ Ranges (`1-5`) work
- ✅ Lists (`9,12,17`) work
- ✅ Specific values (`0`, `15`) work

#### Test 7.4: Invalid Cron Expressions

**Test Procedure:**
1. Create a job with an invalid cron expression:
   ```
   job-schedule:: invalid cron
   ```
2. Start the runner
3. Check the console

**Expected Result:**
- Console shows a warning: "Invalid cron expression for Jobs/test-invalid-cron: 'invalid cron'"
- Job is not registered with the scheduler
- No instances are created

**Verification Checklist:**
- ✅ Invalid expressions are detected
- ✅ Warning is logged
- ✅ Plugin does not crash
- ✅ Other valid scheduled jobs continue to work

---

### Phase 8: OpenClaw Interop

**Goal:** Test import and export of skill definitions in OpenClaw JSON format.

#### Test 8.1: Import OpenClaw Skill

**Test Procedure:**
1. Create a block with the following OpenClaw JSON:
   ```json
   {
     "name": "imported-summarizer",
     "description": "Summarizes text using an LLM",
     "version": "1.0",
     "inputs": ["text", "detail-level"],
     "outputs": ["summary"],
     "steps": [
       {
         "order": 1,
         "action": "llm-call",
         "prompt": "Summarize the following text at {{detail-level}} detail:\n\n{{text}}",
         "model": "openai-model"
       },
       {
         "order": 2,
         "action": "block-insert",
         "config": {
           "target-page": "Summaries",
           "content": "{{step-1-result}}"
         }
       }
     ]
   }
   ```
2. Select the block content (triple-click to select all)
3. Type `/job:import-skill`
4. Select the command

**Expected Result:**
- A new page is created: `Skills/imported-summarizer`
- First block contains:
  ```
  skill-type:: llm-chain
  skill-version:: 1
  skill-description:: Summarizes text using an LLM
  skill-inputs:: text, detail-level
  skill-outputs:: summary
  ```
- Child blocks contain steps in order
- Success message: "Skill 'imported-summarizer' imported successfully"

**Verification Checklist:**
- ✅ JSON is parsed correctly
- ✅ Skill page is created
- ✅ Metadata is mapped to Logseq properties
- ✅ Steps are created as child blocks
- ✅ Prompt templates are preserved
- ✅ Config objects are converted to JSON strings

#### Test 8.2: Import with Unmapped Fields

**Test Procedure:**
1. Import an OpenClaw skill JSON with extra fields not in the Logseq schema:
   ```json
   {
     "name": "test-unmapped",
     "description": "Test skill",
     "version": "1.0",
     "inputs": ["x"],
     "outputs": ["y"],
     "author": "John Doe",
     "tags": ["test", "example"],
     "custom-field": "custom value",
     "steps": []
   }
   ```
2. Import the skill

**Expected Result:**
- Skill is imported
- Unmapped fields are preserved in `openclaw-meta::` property as JSON:
  ```
  openclaw-meta:: {"author": "John Doe", "tags": ["test", "example"], "custom-field": "custom value"}
  ```

**Verification Checklist:**
- ✅ Standard fields are mapped
- ✅ Extra fields are preserved in openclaw-meta
- ✅ Skill is functional (extra fields don't break execution)

#### Test 8.3: Export Logseq Skill to OpenClaw

**Test Procedure:**
1. Navigate to the `Skills/imported-summarizer` page (from Test 8.1)
2. Type `/job:export-skill`
3. Select the command

**Expected Result:**
- A code block is inserted in the current block with OpenClaw JSON:
  ```json
  {
    "name": "imported-summarizer",
    "description": "Summarizes text using an LLM",
    "version": "1",
    "inputs": ["text", "detail-level"],
    "outputs": ["summary"],
    "steps": [
      {
        "order": 1,
        "action": "llm-call",
        "prompt": "Summarize the following text at {{detail-level}} detail:\n\n{{text}}",
        "model": "openai-model"
      },
      {
        "order": 2,
        "action": "block-insert",
        "config": {
          "target-page": "Summaries",
          "content": "{{step-1-result}}"
        }
      }
    ]
  }
  ```

**Verification Checklist:**
- ✅ Skill is exported to valid JSON
- ✅ All properties are mapped back to OpenClaw format
- ✅ Steps are included
- ✅ openclaw-meta fields (if any) are merged back into the export

#### Test 8.4: Roundtrip Fidelity (Import → Export → Import)

**Test Procedure:**
1. Import an OpenClaw skill (from Test 8.1)
2. Export it (from Test 8.3)
3. Copy the exported JSON
4. Delete the skill page (`Skills/imported-summarizer`)
5. Import the JSON again

**Expected Result:**
- The re-imported skill is identical to the original
- All properties, steps, and metadata are preserved

**Verification Checklist:**
- ✅ Import and export are inverse operations
- ✅ No data loss in roundtrip
- ✅ Skill remains functional after roundtrip

---

### Phase 9: Integration

**Goal:** Verify end-to-end functionality and integration of all components.

#### Test 9.1: Full End-to-End Workflow

**Scenario:** Create a scheduled job that queries the graph, summarizes results with an LLM, and writes the summary to a new page.

**Test Procedure:**

1. **Create the Skill: `Skills/daily-summary`**
   - First block:
     ```
     skill-type:: llm-chain
     skill-version:: 1
     skill-description:: Summarize today's journal entries
     skill-inputs::
     skill-outputs:: summary-page
     ```
   - Child block 1:
     ```
     step-order:: 1
     step-action:: graph-query
     step-config:: {"query": "[:find ?content :where [?b :block/page ?p] [?p :block/journal? true] [?p :block/journal-day ?d] [?b :block/content ?content]] ", "limit": 20}
     ```
   - Child block 2:
     ```
     step-order:: 2
     step-action:: llm-call
     step-prompt-template::
     Summarize the following journal entries from today:

     {{step-1-result}}

     Provide a concise summary highlighting key themes and activities.
     step-model:: openai-model
     ```
   - Child block 3:
     ```
     step-order:: 3
     step-action:: page-create
     step-config:: {"page-name": "Daily Summary {{today}}", "content": "{{step-2-result}}"}
     ```

2. **Create the Scheduled Job: `Jobs/daily-summary-job`**
   - First block:
     ```
     job-type:: scheduled
     job-status:: draft
     job-schedule:: 0 18 * * *
     job-skill:: Skills/daily-summary
     job-priority:: 2
     job-max-retries:: 1
     job-created-at:: 2026-02-19T15:00:00Z
     ```

3. **Manual Trigger (for testing without waiting for schedule):**
   - Create: `Jobs/manual-daily-summary`
   - First block:
     ```
     job-type:: manual
     job-status:: queued
     job-skill:: Skills/daily-summary
     job-priority:: 1
     job-created-at:: 2026-02-19T15:05:00Z
     ```

4. **Execute:**
   - Ensure runner is started
   - Wait for the manual job to execute
   - Check the job page for logs
   - Navigate to the created summary page

**Expected Result:**
- Step 1: Graph query returns journal entries
- Step 2: LLM summarizes the entries
- Step 3: A new page is created with the summary
- Job completes successfully
- Log shows all three steps
- Summary page exists and contains the LLM output

**Verification Checklist:**
- ✅ Multi-step skill executes end-to-end
- ✅ Graph query retrieves data
- ✅ LLM processes data
- ✅ New page is created
- ✅ All components work together seamlessly
- ✅ No errors in console
- ✅ Job logs are comprehensive

#### Test 9.2: Verify All Slash Commands

**Test each command:**

1. `/job:run <job-name>` → Enqueues and runs a job
2. `/job:status` → Shows current job runner status
3. `/job:cancel <job-name>` → Cancels a job
4. `/job:create` → Creates a new job page from template (prompts for inputs)
5. `/job:import-skill` → Imports skill from JSON
6. `/job:export-skill` → Exports skill to JSON
7. `/job:mcp-servers` → Lists MCP servers
8. `/job:mcp-tools <server>` → Lists tools on MCP server

**Verification Checklist:**
- ✅ All commands are registered
- ✅ All commands execute without errors
- ✅ Each command shows appropriate success/error messages
- ✅ Commands interact correctly with the runner

#### Test 9.3: Verify Settings Panel

**Test Procedure:**
1. Navigate to: `Settings > Plugin Settings > Logseq AI Hub`
2. Verify all settings are present and editable

**Expected Settings:**
- `Job Runner Enabled` (toggle)
- `Max Concurrent Jobs` (number input)
- `Poll Interval (ms)` (number input)
- `Default Timeout (ms)` (number input)
- `Job Page Prefix` (text input)
- `Skill Page Prefix` (text input)
- `MCP Servers` (text area, JSON)
- `OpenAI API Key` (text input, password)

**Verification Checklist:**
- ✅ All settings render correctly
- ✅ Settings have descriptive labels
- ✅ Settings have default values
- ✅ Changes persist after plugin reload
- ✅ Invalid values (e.g., negative numbers) are rejected or corrected

#### Test 9.4: Verify No Console Errors

**Test Procedure:**
1. Reload the plugin
2. Open browser console
3. Clear console
4. Perform the following actions:
   - Enable job runner in settings
   - Create a skill
   - Create a job
   - Enqueue the job
   - Check job status
   - Cancel the job
   - Connect to an MCP server
   - List MCP tools
5. Review console for errors

**Expected Result:**
- No red error messages
- Only info/debug logs (blue/gray)
- All actions complete successfully

**Verification Checklist:**
- ✅ No JavaScript errors
- ✅ No unhandled promise rejections
- ✅ No "undefined is not a function" errors
- ✅ All API calls succeed

#### Test 9.5: Verify Existing Features Unaffected

**Test Procedure:**
1. Test existing plugin features:
   - `/LLM` command (if it exists)
   - Messaging module commands (if any)
   - Memory module commands (if any)
   - Tasks module commands (if any)
2. Verify they still work as expected

**Verification Checklist:**
- ✅ All pre-existing slash commands work
- ✅ No regressions in existing functionality
- ✅ Job runner does not interfere with other modules
- ✅ Plugin remains stable with job runner disabled

---

## 3. Sample Test Data

Below are complete copy-pasteable Logseq pages for testing. Create these pages in your Logseq graph by copying and pasting the entire content.

### 3.1 Simple Echo Skill

**Page:** `Skills/echo-test`

```
skill-type:: llm-chain
skill-version:: 1
skill-description:: Echoes the input message via LLM
skill-inputs:: message
skill-outputs:: result
- step-order:: 1
  step-action:: llm-call
  step-prompt-template:: Echo this message exactly: {{message}}
  step-model:: openai-model
```

### 3.2 Multi-Step Summarize Skill

**Page:** `Skills/summarize`

```
skill-type:: llm-chain
skill-version:: 1
skill-description:: Summarizes content from the graph
skill-inputs:: query, detail-level
skill-outputs:: summary
- step-order:: 1
  step-action:: graph-query
  step-config:: {"query": "[:find ?content :where [?b :block/content ?content]]", "limit": 10}
- step-order:: 2
  step-action:: llm-call
  step-prompt-template::
  Summarize the following content at {{detail-level}} detail level:

  {{step-1-result}}

  Provide a clear, structured summary.
  step-model:: openai-model
- step-order:: 3
  step-action:: block-insert
  step-config:: {"target-page": "Summaries/{{today}}", "content": "## Summary\n\n{{step-2-result}}"}
```

### 3.3 Manual Job

**Page:** `Jobs/test-manual`

```
job-type:: manual
job-status:: draft
job-priority:: 3
job-skill:: [[Skills/echo-test]]
job-input:: {"message": "Hello from manual job"}
job-max-retries:: 1
job-retry-count:: 0
job-created-at:: 2026-02-19T10:00:00Z
```

**To run:** Change `job-status:: draft` to `job-status:: queued`

### 3.4 Scheduled Job

**Page:** `Jobs/test-scheduled`

```
job-type:: scheduled
job-status:: draft
job-schedule:: */5 * * * *
job-skill:: [[Skills/echo-test]]
job-input:: {"message": "Scheduled run at {{now}}"}
job-priority:: 3
job-max-retries:: 0
job-created-at:: 2026-02-19T10:00:00Z
```

**Note:** This creates instances every 5 minutes. Change to `* * * * *` for every minute (for faster testing).

### 3.5 Job with Dependencies

**Page:** `Jobs/dependency-parent`

```
job-type:: manual
job-status:: queued
job-priority:: 3
job-skill:: [[Skills/echo-test]]
job-input:: {"message": "Parent job"}
job-created-at:: 2026-02-19T10:00:00Z
```

**Page:** `Jobs/dependency-child`

```
job-type:: manual
job-status:: queued
job-priority:: 3
job-depends-on:: [[Jobs/dependency-parent]]
job-skill:: [[Skills/echo-test]]
job-input:: {"message": "Child job - waits for parent"}
job-created-at:: 2026-02-19T10:01:00Z
```

**Test:** Set both to `queued`. Parent should execute first, then child.

### 3.6 MCP Tool Skill

**Page:** `Skills/mcp-file-reader`

```
skill-type:: mcp-tool
skill-version:: 1
skill-description:: Reads a file using MCP filesystem server
skill-inputs:: file-path
skill-outputs:: file-content
- step-order:: 1
  step-action:: mcp-tool
  step-mcp-server:: filesystem
  step-mcp-tool:: read_file
  step-config:: {"path": "{{file-path}}"}
- step-order:: 2
  step-action:: block-insert
  step-config:: {"target-page": "MCP Results", "content": "File content:\n\n{{step-1-result}}"}
```

**To use:** Create a job with `job-input:: {"file-path": "C:\\path\\to\\file.txt"}`

### 3.7 Sample OpenClaw JSON Skill

**Copy this JSON and import via `/job:import-skill`:**

```json
{
  "name": "openclaw-translator",
  "description": "Translates text to another language",
  "version": "2.0",
  "inputs": ["text", "target-language"],
  "outputs": ["translated-text"],
  "author": "OpenClaw Community",
  "tags": ["translation", "llm"],
  "steps": [
    {
      "order": 1,
      "action": "llm-call",
      "prompt": "Translate the following text to {{target-language}}:\n\n{{text}}\n\nProvide only the translation, no explanations.",
      "model": "openai-model"
    },
    {
      "order": 2,
      "action": "block-insert",
      "config": {
        "target-page": "Translations",
        "content": "**{{target-language}} translation:**\n\n{{step-1-result}}"
      }
    }
  ]
}
```

**After import, verify:**
- Skill page `Skills/openclaw-translator` is created
- `openclaw-meta::` contains `{"author": "OpenClaw Community", "tags": ["translation", "llm"]}`
- Steps are created correctly

---

## 4. Troubleshooting Guide

### 4.1 Common Issues and Solutions

#### Issue: Plugin Doesn't Load

**Symptoms:**
- Plugin not listed in Settings > Plugins
- No console messages on plugin load

**Solutions:**
1. Verify the plugin directory path is correct
2. Check `manifest.edn` exists and is valid EDN
3. Reload Logseq
4. Check shadow-cljs watch is running and completed build
5. Look for compilation errors in the shadow-cljs terminal

---

#### Issue: Jobs Not Executing

**Symptoms:**
- Job status stays `queued`
- No log entries appear
- `/job:status` shows jobs in queue but none running

**Solutions:**
1. **Check if runner is enabled:**
   - Settings > Plugin Settings > Logseq AI Hub > Job Runner Enabled = `true`
   - Reload plugin after enabling

2. **Check if runner is started:**
   - Console: `logseq_ai_hub.job_runner.runner.runner_status()`
   - If status is `stopped`, run: `await logseq_ai_hub.job_runner.runner.start_runner()`

3. **Check for errors in console:**
   - Look for red error messages
   - Check for "undefined" or "null" errors when parsing job/skill pages

4. **Verify skill page exists:**
   - Navigate to the skill page referenced by the job
   - Ensure it has all required properties

5. **Check dependencies:**
   - If job has `job-depends-on`, verify dependency jobs are `completed`

6. **Check concurrency limit:**
   - If max concurrent jobs is reached, new jobs wait
   - Check `/job:status` to see how many jobs are running

---

#### Issue: MCP Server Connection Fails

**Symptoms:**
- `/job:mcp-servers` shows status as `disconnected` or `error`
- MCP tool steps fail with "Server not connected" error

**Solutions:**
1. **Verify MCP server is running:**
   - Check the terminal where you started the MCP server
   - Ensure no errors on server startup
   - Test server manually: `curl http://localhost:3001`

2. **Check server URL in settings:**
   - Settings > Plugin Settings > MCP Servers
   - Verify URL matches the server (e.g., `http://localhost:3001`)
   - Check for typos or wrong port numbers

3. **Check transport type:**
   - Ensure `transport` is `streamable-http` (most common)
   - If server uses SSE, change to `sse`

4. **Check CORS settings:**
   - MCP server must allow CORS from Logseq
   - Check server logs for CORS-related errors

5. **Check auth token:**
   - If server requires authentication, ensure `auth-token` is set correctly

6. **Manually reconnect:**
   - Console: `await logseq_ai_hub.job_runner.mcp.client.connect_server({...config...})`

---

#### Issue: Skill Execution Fails with "Template interpolation error"

**Symptoms:**
- Job status becomes `failed`
- Error message mentions missing variables or template interpolation

**Solutions:**
1. **Check variable names:**
   - Ensure `{{variable-name}}` matches an input or step result
   - Variable names are case-sensitive

2. **Check step references:**
   - `{{step-1-result}}` requires step 1 to have completed
   - Step numbers are 1-based (not 0-based)

3. **Check input JSON:**
   - `job-input::` must be valid JSON
   - Use double quotes for strings, not single quotes

4. **Check for typos:**
   - `{{mesage}}` vs `{{message}}` (typo)

5. **Check built-in variables:**
   - `{{today}}` → current date
   - `{{now}}` → current timestamp
   - `{{job-id}}` → job page name

---

#### Issue: Scheduled Jobs Not Creating Instances

**Symptoms:**
- Scheduled job exists but no instances are created
- Time passes but no new job pages appear

**Solutions:**
1. **Check cron expression:**
   - Verify cron syntax is correct (5 fields: minute hour day month weekday)
   - Test with `* * * * *` (every minute) for quick verification

2. **Check job type:**
   - Must be `job-type:: scheduled`

3. **Check scheduler is running:**
   - Console: `logseq_ai_hub.job_runner.scheduler.list_schedules()`
   - Should list your scheduled job

4. **Check for invalid cron warnings:**
   - Console should show warning if cron expression is invalid

5. **Check time alignment:**
   - Scheduler checks every 60 seconds
   - Instances created at the top of each matching minute
   - If you create a job at 10:30:45, the first instance fires at 10:31:00

---

#### Issue: Hot Reload Not Working

**Symptoms:**
- Code changes don't appear in Logseq
- Console doesn't show rebuild messages

**Solutions:**
1. **Check shadow-cljs watch is running:**
   - Terminal should show "Watching..." message
   - Look for "Build completed" after saving files

2. **Manually reload plugin:**
   - Settings > Plugins > Click reload icon next to plugin

3. **Restart watch:**
   - Ctrl+C to stop watch
   - Run `yarn watch` again

4. **Clear browser cache:**
   - Hard reload: Ctrl+Shift+R (Windows) or Cmd+Shift+R (Mac)

---

### 4.2 Debugging via Browser Console

**Accessing the Console:**
- Windows/Linux: `Ctrl + Shift + I` or `F12`
- Mac: `Cmd + Option + I`

**Useful Console Commands:**

```javascript
// Check runner status
logseq_ai_hub.job_runner.runner.runner_status()

// List all queued jobs
logseq_ai_hub.job_runner.runner.list_jobs()

// Read a job page
await logseq_ai_hub.job_runner.graph.read_job_page("Jobs/test-job")

// Read a skill page
await logseq_ai_hub.job_runner.graph.read_skill_page("Skills/test-skill")

// Manually enqueue a job
await logseq_ai_hub.job_runner.runner.enqueue_job("Jobs/test-job")

// Start/stop runner
await logseq_ai_hub.job_runner.runner.start_runner()
await logseq_ai_hub.job_runner.runner.stop_runner()

// List MCP servers
logseq_ai_hub.job_runner.mcp.client.list_servers()

// List tools on a server
await logseq_ai_hub.job_runner.mcp.client.list_tools("filesystem")

// Call a tool directly
await logseq_ai_hub.job_runner.mcp.client.call_tool("filesystem", "read_file", {path: "c:\\test.txt"})
```

**Console Log Filtering:**
- Filter by: `[Job Runner]` to see only job runner logs
- Filter by: `[MCP]` to see only MCP client logs
- Filter by: `error` to see only errors

---

### 4.3 Resetting Job Runner State

If the job runner gets into a bad state (stuck jobs, corrupted queue, etc.), you can reset it:

**Via Console:**

```javascript
// Stop the runner
await logseq_ai_hub.job_runner.runner.stop_runner()

// Clear in-memory state (if exposed)
// Note: This is implementation-dependent; you may need to reload the plugin instead
logseq.App.reloadPlugin("logseq-ai-hub")

// Manually clean up job pages
// Set all stuck jobs to "failed" or "cancelled" status
```

**Via Graph Cleanup:**

1. Search for all pages starting with `Jobs/`
2. For stuck jobs:
   - Change `job-status:: running` to `job-status:: failed` or `cancelled`
3. Delete orphaned job instance pages (if needed)
4. Reload the plugin

**Nuclear Option (Full Reset):**

1. Disable job runner in settings
2. Reload plugin
3. Delete all `Jobs/*` and `Skills/*` pages (or just problematic ones)
4. Re-enable job runner
5. Reload plugin
6. Recreate test data

---

### 4.4 Performance Issues

**Symptoms:**
- Logseq UI becomes slow or unresponsive
- High CPU usage
- Sluggish typing or navigation

**Solutions:**

1. **Reduce poll interval:**
   - Settings > Poll Interval (ms) > Increase from 5000 to 10000 or higher

2. **Reduce concurrency:**
   - Settings > Max Concurrent Jobs > Reduce from 3 to 1

3. **Optimize skills:**
   - Reduce graph query limits
   - Use more specific Datalog queries (avoid returning all blocks)
   - Break large skills into smaller sub-skills

4. **Check for runaway jobs:**
   - Jobs with infinite loops or very long LLM calls
   - Cancel stuck jobs

5. **Disable job runner when not needed:**
   - Settings > Job Runner Enabled > Set to `false`

---

### 4.5 Data Loss Prevention

**Best Practices:**

1. **Backup your graph regularly:**
   - Logseq File > Export graph
   - Keep backups before major testing

2. **Test with draft jobs first:**
   - Always create jobs with `job-status:: draft` initially
   - Change to `queued` only when ready to execute

3. **Use version control for skill definitions:**
   - Export skills to JSON and save them
   - Commit JSON files to a git repository

4. **Monitor job logs:**
   - Check job pages for execution logs before deleting

5. **Test on a separate graph:**
   - Create a test graph for experimentation
   - Don't test destructive operations on your main graph

---

## Conclusion

This manual testing guide covers all phases of the job runner system implementation. Follow the tests sequentially for comprehensive verification, or jump to specific sections to test individual features.

**After completing all tests:**
- ✅ All phases verified
- ✅ No console errors
- ✅ All slash commands functional
- ✅ Settings panel complete
- ✅ Existing features unaffected
- ✅ Full integration working end-to-end

**Ready for production use!**

For issues not covered in this guide, check the implementation code or reach out to the development team.
