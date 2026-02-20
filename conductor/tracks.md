# Tracks: Logseq AI Hub

## Active Tracks

### core-arch_20260209 -- Core Plugin Architecture - Restructure and Extend
- **Status:** planned
- **Branch:** `track/core-arch_20260209`
- **Priority:** P0
- **Estimated:** 8-12 hours (30 tasks across 6 phases)
- **Spec:** [spec.md](tracks/core-arch_20260209/spec.md)
- **Plan:** [plan.md](tracks/core-arch_20260209/plan.md)
- **Description:** Transform the cljs-playground prototype into a production-ready logseq-ai-hub modular architecture. Namespace migration, error handling foundation, placeholder modules (messaging, memory, tasks), expanded settings schema, and testing infrastructure with cljs.test + node-test runner.

### job-runner_20260219 -- Job Runner System: Autonomous Task Execution Engine
- **Status:** planned
- **Branch:** `track/job-runner_20260219`
- **Priority:** P1
- **Estimated:** 35-50 hours (62 tasks across 9 phases)
- **Depends on:** core-arch_20260209 (FR-1, FR-9, FR-10, NFR-2)
- **Spec:** [spec.md](tracks/job-runner_20260219/spec.md)
- **Plan:** [plan.md](tracks/job-runner_20260219/plan.md)
- **Description:** Autonomous task execution engine with job pages, skill pages, MCP server connectivity, OpenClaw skill import/export, priority queue with dependency resolution, cron scheduling, and full skill execution engine with 10 step action types.

## Completed Tracks

(none)
