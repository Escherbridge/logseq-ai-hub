# /LLM Command Guide

The `/LLM` slash command is the primary interface for interacting with large language models directly from your Logseq graph. It supports plain text prompts, page context injection, AI memory recall, and live MCP tool calling -- all from a single block.

## Basic Usage

Type your prompt in a block, then invoke `/LLM`:

```
What is the capital of France?
/LLM
```

The model responds in a child block beneath your prompt. The model used is determined by the **Select Model** setting (default: `llm-model`, which uses the LLM API Key, Endpoint, and Model Name from settings).

## Page Reference Context

Reference any page in your graph with `[[Page Name]]` to inject that page's block content as system prompt context before calling the LLM.

```
[[My Research Notes]] Summarize the key findings
/LLM
```

The plugin fetches all blocks from "My Research Notes", formats them as indented bullets, and passes them to the LLM as background context. The `[[My Research Notes]]` reference is stripped from the prompt -- the model sees only "Summarize the key findings" as the user message.

### Multiple Page References

Reference as many pages as you need:

```
[[Project Spec]] [[Meeting Notes]] [[Architecture]] Compare these and find gaps
/LLM
```

All three pages are fetched in BFS order and included in the system prompt under a `## Context from referenced pages:` header, each page in its own `### Page Name` section.

### Link Traversal with `depth:N`

By default, only the referenced pages themselves are fetched (depth 0). Use `depth:N` to follow `[[links]]` found within those pages:

```
[[Hub Page]] depth:1 Summarize everything including linked sub-pages
/LLM
```

With `depth:1`, the plugin fetches "Hub Page" first, then fetches any pages linked within it (e.g., if "Hub Page" contains `[[Sub Topic A]]` and `[[Sub Topic B]]`, those are also fetched). Depth 2 would follow links found in Sub Topic A and B as well, and so on.

Links to `MCP/` and `AI-Memory/` pages are not followed during traversal -- they are handled by their own systems.

Cycle detection prevents infinite loops: if Page A links to Page B and Page B links back to Page A, each is only fetched once.

### Token Budget with `max-tokens:N`

Control how much page content is injected with a token budget:

```
[[Large Research Paper]] max-tokens:500 Give me a brief summary
/LLM
```

The plugin estimates tokens (characters / 4) and stops fetching pages once the budget is exhausted. Pages are fetched in BFS order, so the first-referenced pages take priority. The default budget is 8000 tokens, configurable in plugin settings.

### Combining Options

Options can appear anywhere in the block alongside page refs:

```
[[Project]] [[Related Work]] depth:2 max-tokens:4000 What are the main themes?
/LLM
```

## AI Memory Context

If the AI Memory system is enabled in settings, reference memory pages with `[[AI-Memory/tag]]` to inject stored memories:

```
[[AI-Memory/project-notes]] [[AI-Memory/coding-style]] Review this approach
/LLM
```

Memory content appears under a `## Context from your memories:` header. The memory system must be enabled in settings for these references to resolve.

## MCP Tool Integration

Reference MCP server pages with `[[MCP/server-name]]` to give the LLM access to that server's tools during the conversation:

```
[[MCP/brave-search]] What's the latest news about AI agents?
/LLM
```

This:
1. Reads the `MCP/brave-search` page to get the server URL and auth config
2. Connects to the MCP server
3. Discovers available tools
4. Calls the LLM with those tools available
5. Executes any tool calls the LLM makes (multi-turn)
6. Returns the final response
7. Disconnects from the server

### MCP Page Format

Create a page named `MCP/your-server` with these properties in the first block:

```
mcp-url:: https://your-server.example.com/mcp
mcp-auth-token:: {{secret.MCP_TOKEN}}
mcp-transport:: streamable-http
```

The `{{secret.KEY}}` syntax references secrets stored in the Secrets Vault setting, so tokens are never exposed in page content.

### Multiple MCP Servers

```
[[MCP/brave-search]] [[MCP/github]] Find open issues about performance
/LLM
```

Tools from all referenced servers are combined and made available to the LLM.

## Sub-Agents

Sub-agents are custom personas defined by Logseq pages. They support all the same reference types.

### Creating an Agent

Write the agent name in a block and invoke `/new-agent`:

```
Code Reviewer
/new-agent
```

This creates a page called "Code Reviewer" with a default system prompt and registers `/llm-code-reviewer` as a slash command. Edit the page content to customize the system prompt.

### Using an Agent with References

Agent commands support `[[Page]]`, `[[MCP/...]]`, and `[[AI-Memory/...]]` references:

```
[[MCP/github]] [[My Coding Standards]] Review PR #42 for style issues
/llm-code-reviewer
```

The agent's page content becomes the system prompt, and it's combined with:
- Graph context from `[[My Coding Standards]]`
- MCP tools from `[[MCP/github]]`

### Managing Agents

- `/refresh-agents` -- Rescans for agent pages and registers new slash commands

## Combining Everything

All three reference types work together in a single block:

```
[[MCP/brave-search]] [[AI-Memory/project-context]] [[Architecture Doc]] depth:1 max-tokens:2000 Research how our architecture compares to industry trends
/LLM
```

This resolves in parallel:
1. **MCP** -- Connects to brave-search, discovers tools
2. **Memory** -- Fetches memories tagged "project-context"
3. **Graph** -- Fetches "Architecture Doc" page + one level of linked pages, up to 2000 tokens

All context is merged into the system prompt, tools are made available, and the LLM processes the full picture.

## Plugin Settings

| Setting | Default | Description |
|---------|---------|-------------|
| LLM API Key | (empty) | Your LLM provider API key |
| LLM API Endpoint | `https://openrouter.ai/api/v1` | API endpoint URL |
| LLM Model Name | `anthropic/claude-sonnet-4` | Model ID for API calls |
| Select Model | `llm-model` | Which registered model to use |
| Page Reference Link Depth | `0` | Default depth for `[[Page]]` link traversal |
| Page Reference Max Tokens | `8000` | Default token budget for page context |
| Enable AI Memory | `false` | Enable the memory system |
| Memory Page Prefix | `AI-Memory/` | Prefix for memory pages |
| Secrets Vault | `{}` | JSON object of secret key-value pairs |

## Tips

- **Large pages**: Use `max-tokens:N` to prevent overwhelming the LLM context window. Start small (500-1000) and increase if the model needs more detail.
- **Depth traversal**: `depth:1` is usually sufficient. Higher depths can pull in a lot of content -- combine with `max-tokens` to stay within budget.
- **Page refs are stripped**: The LLM never sees `[[Page Name]]` in its user message. It only sees the cleaned prompt text and receives page content as system context.
- **Inline options override settings**: `depth:2` in a block overrides the plugin's default depth setting for that single call.
- **MCP server pages**: Name them `MCP/descriptive-name` so the `[[MCP/descriptive-name]]` syntax reads naturally in your blocks.
- **Agent + MCP**: Sub-agents work great with MCP tools. A "Research Assistant" agent with `[[MCP/brave-search]]` can search the web using the agent's custom personality.
- **Secret references**: Use `{{secret.KEY}}` in MCP page auth tokens to keep credentials out of page content. Manage secrets with `/secrets:set`, `/secrets:list`, `/secrets:remove`.
