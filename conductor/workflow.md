# Workflow: Logseq AI Hub

## Development Methodology
- **Test-Driven Development (TDD)**: Write tests first, then implementation
- **Red-Green-Refactor** cycle for all new features

## Git Workflow
- **Branch per track**: `track/<track-id>` branches
- **Commit per task**: One commit per completed task in the plan
- **Commit message format**: `<type>(<scope>): <description>`
  - Types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`
  - Scope: module name (e.g., `agent`, `messaging`, `memory`)
- **Git notes**: Used to annotate commits with Conductor metadata

## Test Coverage
- **Target: 80%** code coverage
- Unit tests for all pure functions
- Integration tests for API interactions (mocked)
- Manual testing checklist per track

## Task Workflow
1. Read the task from `plan.md`
2. Write failing test(s) for the task
3. Implement until tests pass
4. Refactor if needed (tests must still pass)
5. Commit with descriptive message
6. Mark task complete in plan

## Code Review
- Self-review via Conductor reviewer agent
- Focus on: correctness, test coverage, CLJS idioms, security

## Release Process
1. All tracks for release verified
2. `yarn release` produces production build
3. Test production build in Logseq
4. Update version in `package.json`
5. Tag release in git
6. Publish to Logseq Marketplace
