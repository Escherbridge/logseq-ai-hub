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
}
