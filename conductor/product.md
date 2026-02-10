# Product Guide: Logseq AI Hub

## Vision
Logseq AI Hub transforms Logseq into a central orchestration layer for AI workflows, messaging integrations, and automated task management. It bridges the gap between knowledge management and real-time communication by ingesting messages from WhatsApp and Telegram, storing AI-powered memories, and orchestrating automation workflows — all within the Logseq graph.

## Target Users
- **Knowledge workers** who use Logseq as their primary PKM tool and want to unify messaging and AI workflows
- **Developers and power users** who want programmable automation tied to their notes
- **Teams** using Logseq for collaborative knowledge management with external communication channels

## Problems Solved
1. **Fragmented communication**: Messages across WhatsApp, Telegram, and other platforms are siloed and unsearchable within a knowledge graph
2. **Manual AI interactions**: Users copy-paste between AI tools and their notes, losing context
3. **No automation layer**: Logseq lacks native workflow orchestration — users can't trigger actions, schedule tasks, or chain operations
4. **Lost context**: AI conversations and memories aren't persisted alongside related notes

## Key Features

### Core Plugin Architecture
- Pluggable model registry for AI providers (OpenAI, Claude, custom endpoints)
- Settings schema for API keys, endpoints, and model selection
- Slash commands for triggering AI and automation workflows

### Messaging Integration
- WhatsApp Cloud API webhook ingestion
- Telegram Bot API webhook ingestion
- Message storage as Logseq blocks with metadata (sender, timestamp, conversation)
- Conversation pages auto-created per contact/group

### AI Memory
- Persistent AI memory stored as Logseq pages and blocks
- Tag-based categorization and retrieval
- Context-aware responses using graph data as memory
- Slash commands for memory operations (`/ai-memory:store`, `/ai-memory:retrieve`)

### Task Orchestration & Automation
- Workflow engine for chaining operations (message → store → AI process → respond)
- Scheduled tasks (polling, periodic processing)
- User-triggered automation via slash commands
- Event-driven triggers (new message, page update, block change)

## Deployment
- Logseq plugin running in browser context (ClojureScript, shadow-cljs)
- External webhook server hosted on Railway for receiving messaging platform callbacks
- Public plugin available via Logseq Marketplace

## Success Metrics
- **User adoption**: Download count, active installs, community engagement, GitHub stars
- **Workflow efficiency**: Time saved on message processing, task automation, memory retrieval
- **Reliability**: Uptime of webhook server, message delivery rate, error-free operation
