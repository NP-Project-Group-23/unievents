# Stage 1: secure shared Neon integration

## Completed locally

- `.env.local` is ignored by Git and is not present in tracked history.
- Runtime configuration uses `DB_URL`; the validator requires its pooled hostname.
- Migration configuration is separated as `DB_MIGRATION_URL`; the validator requires
  a direct hostname and refuses `NEON_BRANCH=production`.
- The migration helper requires an explicit `-ConfirmIsolatedBranch` switch, runs
  Flyway info before mutation, and validates the schema after V0–V3 are applied.
- Neither helper prints credentials.

## Required account actions

The database password and Realtime secret previously pasted into conversation must
be rotated in Neon. Each member then updates their private `.env.local`. Do not send
the replacement values through chat or commit them.

Create a disposable Neon branch from production. Put that branch's pooled JDBC URL
in `DB_URL`, its direct JDBC URL in `DB_MIGRATION_URL`, and its non-production name
in `NEON_BRANCH`. Then run from the repository root:

```powershell
.\scripts\verify-neon-config.ps1 -RequireMigration
.\scripts\migrate-isolated-neon.ps1 -ConfirmIsolatedBranch
```

After migration, start the backend against the isolated branch and run the complete
tests. Production migration requires team review of the isolated result and a single
designated operator. Normal application startup keeps Flyway disabled.

## Current blocker

The existing `.env.local` has no `DB_MIGRATION_URL` and identifies the production
branch. Therefore no migration or database write was attempted during this stage.
