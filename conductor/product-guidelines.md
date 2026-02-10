# Product Guidelines: Logseq AI Hub

## Brand Voice & Tone
- **Clear and technical**: Users are developers and power users; don't oversimplify
- **Helpful without hand-holding**: Provide enough context to get started, link to docs for depth
- **Honest about limitations**: Be upfront about what requires external services, API keys, or server setup

## Design Standards

### Plugin UI
- Follow Logseq's native UI patterns — no custom modals unless absolutely necessary
- Use Logseq's built-in notification system (`logseq.App.showMsg`) for feedback
- Settings should be self-explanatory with clear descriptions and sensible defaults
- Slash commands should follow the `/namespace:action` pattern (e.g., `/ai-hub:connect`)

### Data Storage
- Store all user data within the Logseq graph (pages and blocks)
- Use consistent page naming conventions (e.g., `[[AI Hub/WhatsApp/Contact Name]]`)
- Metadata stored as Logseq properties on blocks
- Never store API keys or secrets in the graph — use plugin settings only

### Error Handling
- Surface errors clearly via Logseq notifications
- Log detailed errors to browser console for debugging
- Graceful degradation when external services are unavailable
- Never silently fail or lose user data

## Communication Style
- README and documentation written for a developer audience
- Code comments in ClojureScript idiomatic style (docstrings, not inline comments)
- Changelog maintained for each release
- GitHub Issues for bug reports, Discussions for feature requests

## Quality Standards
- All core logic must have unit tests
- Integration tests for API interactions (mocked)
- Manual testing checklist for each release
- No hardcoded API URLs — all configurable via settings
