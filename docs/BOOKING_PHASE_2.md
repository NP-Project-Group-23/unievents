# Phase 2: booking service

`BookingService` exposes `bookSeat(userId, request)`, `getMyBookings(userId)` and
`getMyBooking(userId, bookingId)`. Requests carry positive event and seat IDs.
Results contain the persisted booking ID, a unique `UE-` UUID reference, event ID,
seat ID, status and creation time (an `Instant`, serialized in UTC).

The caller must supply the user ID from an authenticated session or trusted TCP
context. A positive ID is not proof of authentication. The service rejects absent
or nonpositive user IDs, while the database enforces that the user exists.

Booking runs in one Spring transaction: check seat membership, conditionally
reserve the seat, insert and flush the booking. Insertion errors propagate and
roll back the seat update. Call the Spring-managed bean so transaction interception
applies. If a caller supplies an outer transaction, that caller controls the final
commit. Notifications must wait until commit.

Errors have stable codes: `INVALID_REQUEST`, `UNAUTHENTICATED`, `SEAT_NOT_FOUND`,
`SEAT_UNAVAILABLE`, and `BOOKING_NOT_FOUND`. A seat from another event is treated
as not found. Missing bookings and another user's bookings both return
`BOOKING_NOT_FOUND`. Infrastructure failures are not mislabeled as seat conflicts.

History is newest first, defaults to page 0 with 20 results, and returns a `Page`
with total counts. Use `getMyBookings(userId, page, size)` for subsequent pages;
size is limited to 1–100. Both history and individual retrieval are scoped by user.

## Integration boundaries

This phase adds a service, not HTTP or TCP endpoints. Event existence, booking
windows and cancellation rules need M2's event module; currently only seat/event
membership is checked. The Phase 1 migration still needs execution setup and event
foreign keys before shared deployment. Retry/idempotency, cancellation and
notifications remain later integration work.

## Isolated validation

From `backend`:

```powershell
.\mvnw.cmd '-Dtest=BookingServiceTests,BookingPersistenceTests,AuthFlowTests,AuthIntegrationTests' test
```

The service tests use the actual migration on an isolated H2 database and call the
Spring-managed service without a wrapping test transaction. They cover success,
already-booked rejection, missing seats/wrong events, private paginated history,
invalid inputs, rollback after an insert failure, and two concurrent service calls
producing exactly one committed booking. Existing persistence and authentication
tests run alongside them. PostgreSQL concurrency validation remains pending.

Recorded on 2026-11-17: BUILD SUCCESS; 14 tests passed, 0 failures, 0 errors,
0 skipped (7 service, 4 persistence, 3 authentication).

The configured-database application context test is excluded to keep this run
isolated from shared Neon data.
