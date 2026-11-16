# Member 3: Phase 1 persistence

The `seat` and `booking` packages provide JPA entities and repositories. IDs for
users and events remain scalar values because those modules do not expose JPA
entities. The SQL migration references the existing `app_users(id)` table.

`V1__create_seats_and_bookings.sql` is a pending integration migration. Flyway is
not configured, so placing it under `db/migration` does not execute it at startup.
Before deployment, confirm the event table and primary key with M2, add event
foreign keys to both tables, and coordinate migration numbering and execution
with M5. Test against an isolated PostgreSQL branch before applying to shared data.
Do not use Hibernate schema generation as a substitute: the migration includes
foreign keys and checks that are not all expressed by the entity mappings.

## Transaction contract

The later booking service must open one transaction, validate the authenticated
user and event, call `reserveIfAvailable(seatId, eventId)`, require a result of 1,
and insert a booking with a unique reference. A result of 0 means unavailable or
no matching seat. Throw on failure so the whole transaction rolls back. Publish
notifications only after commit. The repository requires an existing transaction
and clears the persistence context after the bulk update; reload affected entities.

The database enforces unique seat labels within each event, one permanent booking
per seat, unique references, valid statuses, an existing user, and matching seat
and booking event IDs. Reservation plus booking consistency depends on the service
transaction; directly inserting a booking does not change seat status.

History queries accept pagination and user-scoped lookups are available. The
service must obtain the user ID from authentication, not a client-supplied ID.
Cancellation, rebooking, API authorization, retry/idempotency, TCP integration and
notification delivery are outside this persistence phase.

## Validation (2026-11-16)

Run from `backend`:

```powershell
.\mvnw.cmd '-Dtest=BookingPersistenceTests,AuthFlowTests,AuthIntegrationTests' test
```

The four persistence tests execute the actual migration on H2 in PostgreSQL mode
and validate the JPA mappings. They cover persisted reservation/history, unique
and relationship constraints, rollback after a failed booking, and two independent
concurrent transactions competing for one seat (exactly one committed booking).
The authentication fixture mirrors `app_users` with portable timestamp spelling.

Recorded result: BUILD SUCCESS; 7 tests run, 0 failures, 0 errors, 0 skipped
(4 persistence, 2 authentication flow, 1 authentication integration).

H2 results are not PostgreSQL concurrency evidence. PostgreSQL migration and race
testing remain an integration gate. The existing `UnieventsBackendApplicationTests`
uses the configured database and is intentionally excluded from this isolated run.
