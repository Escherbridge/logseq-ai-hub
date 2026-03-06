import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";

export function registerPrompts(server: McpServer): void {
  server.prompt(
    "summarize_page",
    "Summarize a Logseq page - reads the page and provides a concise summary",
    { page: z.string().describe("Name of the Logseq page to summarize") },
    async ({ page }) => ({
      messages: [
        {
          role: "user" as const,
          content: {
            type: "text" as const,
            text: `Please summarize the Logseq page "${page}". Use the page_read tool to read the page content first, then provide a concise summary highlighting key points, tasks, and connections to other pages.`,
          },
        },
      ],
    }),
  );

  server.prompt(
    "create_skill_from_description",
    "Generate a skill definition from a natural language description",
    { description: z.string().describe("Natural language description of what the skill should do") },
    async ({ description }) => ({
      messages: [
        {
          role: "user" as const,
          content: {
            type: "text" as const,
            text: `Create a Logseq AI Hub skill definition based on this description: "${description}"

Use the skill_create tool to create it. The skill should have:
- A clear, descriptive name (kebab-case)
- An appropriate type (automation, query, transform, notification)
- A detailed description
- Well-defined inputs and outputs

First use skill_list to see existing skills for naming conventions, then create the skill.`,
          },
        },
      ],
    }),
  );

  server.prompt(
    "analyze_knowledge_base",
    "Analyze the Logseq knowledge base for patterns and insights",
    { focus: z.string().optional().describe("Optional focus area for the analysis") },
    async ({ focus }) => ({
      messages: [
        {
          role: "user" as const,
          content: {
            type: "text" as const,
            text: focus
              ? `Analyze my Logseq knowledge base with a focus on "${focus}". Use page_list to discover relevant pages, then page_read to examine their content. Identify patterns, connections, gaps, and actionable insights related to ${focus}.`
              : `Analyze my Logseq knowledge base for patterns and insights. Use page_list to discover pages, then page_read to examine interesting ones. Identify key themes, frequently referenced topics, potential gaps, and suggest ways to better organize or extend the knowledge base.`,
          },
        },
      ],
    }),
  );

  server.prompt(
    "draft_message",
    "Draft a message for a contact using knowledge base context",
    {
      contact: z.string().describe("Contact name or identifier"),
      context: z.string().describe("Context or topic for the message"),
    },
    async ({ contact, context }) => ({
      messages: [
        {
          role: "user" as const,
          content: {
            type: "text" as const,
            text: `Draft a message for ${contact} about: ${context}

First use contact_list to find the contact, then message_list to see recent conversation history. Also search the knowledge base using graph_search for relevant context about "${context}". Then compose an appropriate message that's informed by the conversation history and knowledge base context.

Present the draft for review before sending.`,
          },
        },
      ],
    }),
  );

  server.prompt(
    "code_review",
    "Perform a code review using project context, ADRs, and a diff",
    {
      project: z.string().describe("Project name for context lookup"),
      diff: z.string().optional().describe("Git diff or code changes to review"),
      focus: z.string().optional().describe("Specific focus areas for the review"),
    },
    async ({ project, diff, focus }) => ({
      messages: [
        {
          role: "user" as const,
          content: {
            type: "text" as const,
            text: `Perform a code review for project "${project}".

${diff ? `## Changes to Review\n\`\`\`\n${diff}\n\`\`\`` : "Use graph_search to find recent changes or ask for the diff."}

${focus ? `## Focus Areas\n${focus}` : ""}

## Review Process
1. Use project_get to understand the project's architecture and conventions
2. Use adr_list to check for relevant Architecture Decision Records
3. Use lesson_search to find past lessons learned for this project
4. Evaluate the changes against:
   - Architecture alignment (ADRs)
   - Security best practices
   - Test coverage expectations
   - Code style and conventions
   - Performance implications
5. Use safeguard_check if the changes involve sensitive operations
6. Provide structured feedback with severity levels (critical, warning, suggestion)`,
          },
        },
      ],
    }),
  );

  server.prompt(
    "start_coding_session",
    "Initialize a coding session with full project context",
    {
      project: z.string().describe("Project to work on"),
      task: z.string().optional().describe("Specific task or feature to work on"),
    },
    async ({ project, task }) => ({
      messages: [
        {
          role: "user" as const,
          content: {
            type: "text" as const,
            text: `Starting a coding session for project "${project}"${task ? ` to work on: ${task}` : ""}.

## Setup Steps
1. Use project_get to load project configuration and architecture context
2. Use adr_list to review recent Architecture Decision Records
3. Use lesson_search to find relevant lessons learned${task ? ` related to "${task}"` : ""}
4. Use work_list_claims to check for active work claims that might conflict
5. Use track_list to see active tracks and their status
6. Use safeguard_policy_get to understand the project's safeguard level

## Then
- Summarize the project context, active work, and any relevant lessons
- If a task is specified, create a work claim for the files you'll be working on
- Recommend an approach based on ADRs and lessons learned
- Flag any safeguard policies that may require approvals`,
          },
        },
      ],
    }),
  );

  server.prompt(
    "deployment_checklist",
    "Generate a deployment checklist with safeguard verification",
    {
      project: z.string().describe("Project to deploy"),
      environment: z.string().optional().describe("Target environment (staging, production)"),
    },
    async ({ project, environment }) => ({
      messages: [
        {
          role: "user" as const,
          content: {
            type: "text" as const,
            text: `Prepare a deployment checklist for project "${project}"${environment ? ` to ${environment}` : ""}.

## Checklist Steps
1. Use project_get to verify project status and configuration
2. Use track_list to ensure all planned tracks are complete
3. Use project_dashboard to check task completion percentages
4. Use safeguard_check to verify deployment is allowed under current policy
5. Use adr_list to check for deployment-related ADRs
6. Use lesson_search with category "deployment" for past deployment lessons

## Generate
- A pre-deployment checklist with pass/fail items
- Any required approvals (via safeguard_request if needed)
- Post-deployment verification steps
- Rollback procedure reference`,
          },
        },
      ],
    }),
  );
}
