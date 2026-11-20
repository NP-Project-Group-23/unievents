# Member 3: administrative event booking manifest

## Implemented

`EVENT_BOOKINGS` provides an administrator with a paginated booking manifest for
one event. The operation requires an authenticated `ADMIN` session, validates the
event and pagination bounds, and returns bookings newest first. Student sessions
receive `FORBIDDEN`; an unknown event receives `EVENT_NOT_FOUND`.

The operation is registered in the TCP dispatcher and permitted through the
Next.js server gateway. The response intentionally uses the existing BookingResult
contract and does not expose passwords, session tokens, or idempotency keys.

The Event Studio now includes a Bookings action for every event and a responsive,
paginated attendee manifest showing booking reference, user ID, seat ID, timestamp,
and status. Loading, empty, error, and page navigation states use the existing UI
patterns. The table calls `EVENT_BOOKINGS` through the Next.js TCP gateway and does
not bypass the network-programming path.
