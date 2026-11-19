# Step 4: events, seat inventory and migrations

## Implemented

The shared TCP dispatcher registers CREATE_EVENT, UPDATE_EVENT, DELETE_EVENT,
LIST_EVENTS and GET_EVENT. Reads require a valid session; mutations require ADMIN.
EventService also looks up the actor's current database role before mutations,
so calling the service directly does not bypass admin authorization.

Events store title, description, venue, UTC start time, capacity, creator and creation
time. Input validation follows PROTOCOL.md, including integer IDs/pagination,
capacity 1–1,000 and future start times. LIST_EVENTS returns a stable page DTO,
ordered by start time and ID. Oversized responses return a bounded error asking
for a smaller page, rather than writing a truncated frame.

Creating an event and all its seats is one transaction. Seat labels are A1–A8,
B1–B8, etc.; 40 seats produce the planned 5×8 inventory. Labels continue AA, AB,
etc. after Z. Capacity cannot be edited through UPDATE_EVENT. Other metadata is
updated under an exclusive event lock. Deletion checks for bookings, removes seats,
and removes the event in the same transaction; any confirmed booking prevents deletion.

BookingService acquires a shared event lock before checking event time and reserving
a seat. Event mutations acquire an exclusive lock, in the same event-before-seat
order. This coordinates service writes without replacing the existing atomic seat
update or uniqueness constraints. PostgreSQL can share the event read lock between
bookings; H2 may implement stronger locking. The migration also enforces event foreign
keys. A missing event now returns EVENT_NOT_FOUND; started events reject booking
with VALIDATION_FAILED. API booking handlers remain the next step.

## Migration inventory

| Version | Purpose |
| --- | --- |
| V0 | Create app_users for fresh installs |
| V1 | Existing seat/booking schema; unchanged |
| V2 | Add/validate user roles and create events |
| V3 | Add event foreign keys to seats and bookings |

Flyway dependencies and a Maven plugin are configured. Normal application startup
uses Hibernate validation and **does not run SQL initialization or migrations**.
The legacy schema.sql remains for reference only. The `migrate` Spring profile
explicitly enables Flyway using DB_MIGRATION_URL. Baseline-on-migrate is false;
clean is disabled. Do not edit V1 or mark an unverified schema as already migrated.

An unmigrated database will fail normal startup validation until it is upgraded.
That is intentional: no event tables or fake event records are silently generated.

## Run migrations on an isolated Neon branch first

Use the existing project and a direct (non-pooler) JDBC URL for the target branch.
No shared database was inspected or changed by this implementation. Actual adoption
depends on inspecting its schema and migration history, not on assumptions here.

From `backend`, set DB_MIGRATION_URL, DB_USERNAME and DB_PASSWORD privately in the
process environment. The Maven plugin does not load `.env.local` automatically.
The Spring application does load it. Never put passwords in command arguments.

```powershell
.\mvnw.cmd flyway:info
.\mvnw.cmd flyway:migrate
.\mvnw.cmd flyway:validate
```

These are executable migration commands, not read-only checks except info/validate.
Use them only after choosing the intended isolated database and adoption path.
After migration, start normally using DB_URL for the application connection.
Alternatively, for a fresh or already tracked compatible database, start with
`-Dspring-boot.run.profiles=migrate` to run migrations before normal application
startup; this profile requires DB_MIGRATION_URL and starts the server afterward.

### Existing schemas without Flyway history

First inspect tables, columns, keys, constraints, row counts and existing event IDs.
Keep a backup/restore point and validate the following on a branch. These baseline
commands are conditional operator actions, not automatic setup:

- Authentication-only schema matching app_users: baseline at **0**, then migrate.
  This skips V0 and executes V1–V3. V2 adds the role column if missing.
- Complete schema matching V1 (users, seats and bookings): baseline at **1**.
  Apply only V2 initially if there are seat/booking rows with no corresponding events.
- Empty schema: do not baseline; migrate V0–V3 normally.
- Existing history or divergent/partial schema: inspect and reconcile explicitly;
  do not apply one of these baselines blindly.

Example only after verifying the matching V1 schema:

```powershell
.\mvnw.cmd '-Dflyway.baselineVersion=1' flyway:baseline
.\mvnw.cmd '-Dflyway.target=2' flyway:migrate
```

For populated inventory, author a reviewed data migration between V2 and V3 (for
example V2.1, before V3 has been applied to that installation), creating genuine
events for the existing IDs, titles, times, owners and capacities. Explicit identity
inserts require identity-sequence reconciliation. Reject inconsistent capacities
or seat mappings; do not delete bookings or invent event metadata. Validate there
are no orphan event IDs, then migrate to V3. New migrations must be reviewed across
environments; never insert an older version into environments that have already
passed it without a coordinated migration plan. Existing invalid roles also require
a deliberate data correction before V2's role check can pass.

V3 deliberately fails if orphan inventory exists. A failed H2 DDL migration can
leave partial DDL/history; discard the isolated test database. PostgreSQL migration
behavior must be verified separately before shared deployment.

## Verification

Verified 2026-11-19: BUILD SUCCESS; 121 tests run, 0 failures, 0 errors, 0 skipped.
This is the selected server regression suite, including H2/Flyway and real local
TCP tests; it excludes JavaFX UI tests and is not PostgreSQL evidence.

Run from backend:

```powershell
.\mvnw.cmd '-Dtest=*Tests,RequestDispatcherTest,FrameCodecTest,TcpServerClientTest,AuthHandlersIntegrationTest,T01ComprehensiveEdgeCaseTest,SessionManagerTest,StudentAuthServiceTest,UserRepositoryTest' test
```

New tests exercise event CRUD through real TCP, student/anonymous rejection, server
validation, 40-seat generation, capacity immutability, rollback of event and seats
when a seat insert fails, deletion protection, and past-event booking rejection.
Migration tests cover fresh installation, repeat execution, upgrade from V1 with
preserved users, and orphan-inventory refusal. Existing booking fixtures now run
Flyway and create valid event rows instead of bypassing event foreign keys.

An existing executor test was corrected to await executor completion: its task
latch fires before afterExecute records the final completed-task count.
H2 migrations use pooled connections like the application because H2 check
expressions can retain the creating session. No production SQL was changed to
work around this H2 test behavior.

PostgreSQL migration/locking verification, live-database adoption, JavaFX event
screens and the full desktop demo remain pending. This step implements the server
event operations and migration machinery, not those later UI/deployment gates.
