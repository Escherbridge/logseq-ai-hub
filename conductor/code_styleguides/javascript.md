# JavaScript Reference Guide

This guide covers conventions for the compiled JavaScript output and any JS interop code.

## Relevant for
- shadow-cljs configuration
- npm package management
- Build output analysis
- External JS libraries consumed via `:require`

## Conventions
- **package.json**: Use `yarn` for dependency management
- **Build output**: `main.js` in project root (shadow-cljs browser target)
- **No hand-written JS**: All application code is ClojureScript
- **Node.js server**: If needed, uses shadow-cljs `:node-script` target

## Interop Patterns
- When consuming npm packages, use shadow-cljs `:npm-deps` or `:js-provider`
- Prefer CLJS wrappers over raw JS interop when available
- Document any JS libraries added to `package.json` with purpose
