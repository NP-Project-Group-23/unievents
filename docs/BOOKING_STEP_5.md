# Step 5: booking TCP operations and commit handoff

## Implemented

The shared dispatcher now serves `GET_SEATS`, `BOOK_SEAT`, `MY_BOOKINGS` and
`GET_BOOKING`. Every operation requires a freshly validated session. Booking and
history ownership always comes from that session; a caller-supplied user ID is
never authoritative.

`GET_SEATS` returns a stable, ID-ordered page of seat DTOs and verifies that the
event exists. The booking handlers return bounded DTO responses rather than JPA
entities. Missing IDs, invalid pagination and non-object payloads return
`INVALID_REQUEST`. A lost atomic reservation maps to `SEAT_ALREADY_BOOKED`.
Another user's booking is intentionally indistinguishable from a missing booking
and returns `BOOKING_NOT_FOUND`.

After the booking transaction commits, `BookingService` publishes
`BookingCommitted(bookingId,eventId,seatId,committedAt)`. It contains no student
identity. Rollback does not publish it, and a notification listener failure is
logged without reversing the committed booking or changing its successful result.
This is the internal handoff for the next UDP stage.

## Verification

`BookingProtocolIntegrationTests` uses the real TCP client/server path and verifies:

- session enforcement, pagination and input validation;
- session-derived booking ownership despite a spoofed payload field;
- one successful reservation followed by `SEAT_ALREADY_BOOKED`;
- private history and booking lookup;
- one post-commit event, no event on rollback, and listener-failure isolation.

Verification on 2026-11-19: the focused suite passed 3/3 tests, and the complete
backend suite passed 132 tests with 0 failures, 0 errors and 0 skipped.

## Remaining work

Step 6 is the M4 UDP subscription and notification path. It should consume
`BookingCommitted`, maintain authenticated expiring subscriptions, serialize
per-event sequence assignment and send only the identity-free `SEAT_CHANGED` hint.
Clients must refresh authoritative seats through `GET_SEATS` after a datagram.
Shared PostgreSQL concurrency verification and desktop UI integration also remain.
