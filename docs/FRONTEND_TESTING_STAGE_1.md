# Missing implementation Stage 1: frontend automated testing

## Implemented

- Vitest 3 with a Node 20-compatible jsdom and Testing Library stack.
- `npm test` and `npm run test:watch` commands.
- TCP gateway transport tests for newline framing, tokens, timeouts, and frame-size limits.
- Next.js gateway route tests for operation allow-listing, protected requests, HttpOnly login cookies, token redaction, conflict mapping, and expired-session cleanup.
- Portal tests for login/registration states and user-visible booking-conflict recovery.
- Deterministic Turbopack root configuration and generated-output lint exclusions.

## Verification

Verified on 2026-11-19:

- 3 test files passed.
- 9 tests passed.
- ESLint passed.
- Next.js production build and TypeScript checks passed.

Run from `frontend`:

```powershell
npm test
npm run lint
npm run build
```

## Remaining testing work

This stage protects the frontend and gateway boundaries without requiring a running Java backend. A future browser-level acceptance suite should start an isolated backend and exercise admin creation, two-student contention, history, and reconnect through a real browser.
