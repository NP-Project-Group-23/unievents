# Phase 3: synchronization and concurrent booking

## How the implementation coordinates requests

`BookingService` is a shared Spring bean. Its fields hold repository references;
user IDs, requests, booking references and results are local to each invocation.
It does not store mutable per-request data in shared fields. Each worker calls the
Spring-managed service proxy, which starts the booking transaction for that call.
No entity instance or transaction is passed between worker threads.

`@Transactional` groups the seat update and booking insert into one commit or
rollback. It does not itself serialize competing calls. The conditional update in
`SeatRepository.reserveIfAvailable` is the contention boundary:

```sql
UPDATE seats SET status = 'BOOKED'
WHERE id = :seatId AND event_id = :eventId AND status = 'AVAILABLE';
```

The service accepts only an affected-row count of 1. A zero count produces
`SEAT_UNAVAILABLE`. The earlier seat lookup checks membership, but is not the
reservation decision: another transaction can change the seat after that lookup.
The unique constraint on `bookings.seat_id` supplies an additional database guard.
An insertion failure propagates through the service proxy and rolls back the seat
update, as covered by the Phase 2 rollback test.

There is no Java `synchronized` block or `ReentrantLock` in the production booking
path. A JVM monitor would coordinate only callers sharing that monitor, and would
not cover another server process. A lock around every booking would also serialize
unrelated seats. The intended cross-instance protection is the database operation
and constraints; multi-instance PostgreSQL verification is still pending.

For the coursework demonstration, distinguish the Java synchronization primitives
used by the test harness from the database concurrency control used by the service.
If the assessment specifically requires a production Java monitor/lock, the current
implementation does not demonstrate that requirement.

## Concurrent test design

`BookingConcurrencyTests` uses ten distinct persisted users, a fixed pool of ten
Java threads, and a database connection pool with a maximum of ten connections.
One `CountDownLatch` confirms all workers are ready; another releases their calls
together. Each worker returns an immutable outcome through a `Future`, so workers
do not update shared result counters or lists. The coordinator reads results after
completion. Bounded waits, cancellation and executor shutdown handle failures.

Each scenario runs five times with fresh fixture rows:

| Scenario | Required result on every repetition |
| --- | --- |
| Ten users request one seat | One confirmation, nine `SEAT_UNAVAILABLE` errors, one persisted booking, seat `BOOKED` |
| Ten users request ten different seats | Ten confirmations, ten unique references, correct user/seat ownership, all seats `BOOKED` |

Unexpected exceptions fail the test; they cannot be counted as ordinary rejected
bookings. Assertions inspect committed records after service calls return. There
is no wrapping test transaction. The tests execute the actual Phase 1 migration
and validate JPA mappings on an isolated H2 database in PostgreSQL mode.

These are concurrent service clients within one JVM, not TCP connections. A start
gate encourages contention but cannot guarantee simultaneous database execution.
The different-seat test verifies successful concurrent submissions; it does not
measure throughput or prove the absence of all serialization.

## Reproduce and interpret verification

From `backend`:

```powershell
.\mvnw.cmd '-Dtest=BookingConcurrencyTests,BookingServiceTests,BookingPersistenceTests,AuthFlowTests,AuthIntegrationTests' test
```

The configured-database context test is excluded from this isolated run. Maven
reports are written to `backend/target/surefire-reports`.

Verified on 2026-11-18: BUILD SUCCESS; 24 tests run, 0 failures, 0 errors,
0 skipped. This includes ten concurrency executions (two scenarios repeated five
times) plus fourteen service, persistence and authentication regression tests.
Every same-seat repetition produced one winner and nine expected rejections;
every different-seat repetition produced ten committed bookings.

H2 evidence does not establish PostgreSQL lock behavior. The opt-in
`PostgresBookingConcurrencyTests` now runs the scenarios against a disposable
PostgreSQL database or isolated Neon branch with the actual migrations and
independent connections; see `MEMBER3_POSTGRES_CONCURRENCY_STAGE.md`.
Also run competing calls from separate application instances. Do not point these
fixtures at shared team data: setup deletes all rows in their test tables.

Event foreign keys, migration execution setup, retry/idempotency and TCP integration
remain the integration work described in the earlier phase documents.
