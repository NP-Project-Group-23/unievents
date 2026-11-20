# Member 3: durable booking retries

## Implemented

- `BOOK_SEAT` accepts an optional legacy-compatible `idempotencyKey`; current
  clients always send one and reuse it after an uncertain response.
- Bookings persist the key, with a unique constraint scoped to the authenticated
  user. Existing rows and older clients remain valid because the column is nullable.
- A repeated key with the same event and seat returns the original booking without
  reserving again or publishing a second committed-booking event.
- A repeated key with a different event or seat is rejected as `INVALID_REQUEST`.
- The service checks before and after acquiring the event booking lock, allowing
  simultaneous retries to converge on the committed result.
- Booking timestamps are normalized to PostgreSQL microsecond precision before
  insert so the first response and a later replay are identical.

## Database upgrade

Flyway migration `V4__add_booking_idempotency.sql` adds `idempotency_key` and the
`uq_bookings_user_idempotency` constraint. It runs automatically at backend startup.
Use the normal application database configuration; do not execute the SQL manually.

## Verification

Focused backend tests cover fresh and incremental migrations, sequential replay,
simultaneous replay, payload mismatch, invalid keys, protocol parsing, and exactly
one commit notification. The frontend test verifies that a failed/uncertain attempt
reuses its original key.
