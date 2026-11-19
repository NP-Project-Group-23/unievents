# UniEvents TCP protocol contract

Contract baseline: 2026-11-19. This is the integration target for all five members,
based on the supplied UniTicket workload plan and existing Java protocol classes.
It is not a claim of teammate review or completed runtime integration. Differences
from current code are listed below. See [architecture](ARCHITECTURE.md) for ownership.

## Transport and envelopes

Reuse `network.protocol.RequestEnvelope`, `ResponseEnvelope`, `ErrorCode` and
`FrameCodec`; do not introduce another envelope for individual operations.
Send one compact UTF-8 JSON object followed by LF. Embedded newlines in strings
must be JSON escapes. Maximum JSON frame size is 65,536 UTF-8 bytes, excluding LF.
The reader currently accepts CRLF, skips blank lines and accepts a final JSON frame
at EOF; clients must always send LF. TCP reads are not message boundaries.
Oversized frames produce `FRAME_TOO_LARGE` where possible, then close the connection.
Malformed JSON produces `MALFORMED_JSON`; a valid next frame may still be processed.

Request fields:

| Field | Contract |
| --- | --- |
| `requestId` | Client-generated UUID string, unique among outstanding requests; echoed in response |
| `type` | Uppercase operation from the table below; existing dispatcher accepts case-insensitive names |
| `token` | Top-level session token for protected operations; omit for public operations |
| `payload` | Operation-specific JSON object; use `{}` for empty inputs |

The existing constructor generates an ID if absent/blank. Clients must provide
their own ID to correlate responses. Errors before an ID can be recovered and
connection rejection before a request is read use a server-generated ID. Clients
must treat uncorrelated overload/framing errors as connection-level errors.

Success fields: `requestId`, `status: "SUCCESS"`, `data`.
Error fields: `requestId`, `status: "ERROR"`, `errorCode`, `errorMessage`.
Null fields are omitted. Responses have no `type` field. A success must not carry
an error code; an error must not carry success data. No new version field is added
to the current records; incompatible changes require a coordinated contract change.

Database IDs are positive signed 64-bit JSON integers. Domain timestamps are ISO
8601 UTC strings. Existing diagnostic `PING.timestamp` remains epoch milliseconds.
Never serialize JPA entities or Spring `Page` directly as the public contract.

## Authentication and authorization

- `REGISTER` creates a STUDENT by default and returns user details, not a session.
  Call `LOGIN` next. Existing `id` in registration and `userId` in login are retained.
- `LOGIN` returns a token and identity. Send the token on every protected request.
  Clients must not provide the booking owner's user ID or authoritative role.
- Validate token liveness for each protected operation. Missing/unknown/revoked
  tokens return `UNAUTHENTICATED`; known expired tokens may return `SESSION_EXPIRED`.
  Both require clearing the client session and logging in again.
- Never fall back to a cached socket identity when token validation fails. Pass only
  a freshly verified identity to handlers. Valid student sessions attempting admin
  operations receive `FORBIDDEN`.
- Existing `payload.token` support is legacy compatibility; new clients use only
  top-level `token`. If both exist, top-level wins.
- Sessions currently expire after 30 idle minutes and are held in server memory.
  Disconnect does not itself log out. Reconnect calls `SESSION_CHECK`; a server
  restart loses sessions and requires login. Multi-server session sharing is not implemented.
- `LOGOUT` invalidates the supplied token. A repeated logout currently returns
  `UNAUTHENTICATED`; clients still clear local state.
- Public registration cannot assign ADMIN. The existing admin-registration path
  must validate a live ADMIN token, not trust a previously cached connection role.
  Initial administrator provisioning is a separate operator setup step.
- Do not return password hashes, SQL details, stack traces or tokens in logs.
  Generic `INTERNAL_ERROR` messages accompany server-side diagnostic logging.

## Operations and ownership

"Present" means handler source exists, not that normal startup serves it over TCP.
"Pending" means the following shape is the implementation target.

| Operation | Owner | Access | Payload | Success `data` | State |
| --- | --- | --- | --- | --- | --- |
| `PING` | M1 | Public | `{}` | `{message:"PONG",timestamp:epochMillis}` | Present |
| `ECHO` | M1 | Public diagnostic | JSON object | Same payload | Present |
| `REGISTER` | M1 | Public STUDENT; ADMIN assignment requires ADMIN | `name,email,password,role?` | `id,name,email,role` | Present |
| `LOGIN` | M1 | Public | `email,password` | `token,userId,name,email,role` | Present |
| `LOGOUT` | M1 | Session | `{}` | `message` | Present |
| `SESSION_CHECK` | M1 | Session | `{}` | `valid,token,userId,email,role` | Present |
| `LIST_EVENTS` | M2 | Session | `page?,size?` | Page of EventSummary | Registered in Step 4 |
| `GET_EVENT` | M2 | Session | `eventId` | EventSummary | Registered in Step 4 |
| `CREATE_EVENT` | M2 | ADMIN | `title,description?,venue,startsAt,capacity` | EventSummary | Registered in Step 4 |
| `UPDATE_EVENT` | M2 | ADMIN | `eventId,title,description?,venue,startsAt` | EventSummary | Registered in Step 4; omitted description clears it |
| `DELETE_EVENT` | M2 | ADMIN | `eventId` | `eventId,deleted:true` | Registered in Step 4 |
| `GET_SEATS` | M3 | Session | `eventId,page?,size?` | `eventId,items:[SeatSummary],page,size,totalElements,totalPages` | Registered in Step 5 |
| `BOOK_SEAT` | M3 | Session | `eventId,seatId` | BookingResult | Registered in Step 5 |
| `MY_BOOKINGS` | M3 | Session | `page?,size?` | Page of BookingResult for verified user | Registered in Step 5 |
| `GET_BOOKING` | M3 | Session | `bookingId` | BookingResult for verified user | Registered in Step 5 |
| `SUBSCRIBE_EVENT` | M4 | Session | `eventId,udpPort` | `subscriptionId,eventId,expiresAt,streamId` | Registered in Step 6 |
| `UNSUBSCRIBE_EVENT` | M4 | Session | `subscriptionId` | `subscriptionId,removed` | Registered in Step 6 |
| `GET_METRICS` | M5 | ADMIN | `{}` | ServerMetricsSnapshot | Registered; TCP/HTTP permissions verified in Step 3 |

Pagination: zero-based `page`, default 0; `size` default 20, range 1–100. Page:
`{items:[],page:0,size:20,totalElements:0,totalPages:0}`. Counts must be real.
If a response cannot fit one frame, fail with a bounded error rather than truncating
JSON. Clients can request a smaller page; an oversized single item is a server bug.

EventSummary: `eventId,title,description,venue,startsAt,capacity,createdBy,createdAt`.
Title 1–150 characters, description at most 2,000, venue 1–200, capacity 1–1,000.
Trim textual fields; create/update requires a future `startsAt` with timezone.
Creator comes from the session. Capacity is immutable after creation in this MVP;
seat generation uses labels A1…A8, B1…B8, etc. A 40-seat event yields the planned 5×8
grid; larger events require paginated grid rendering. Event lists sort by startsAt,
then eventId; seats by numeric ID; history by createdAt descending, then ID descending.
Unknown events return `EVENT_NOT_FOUND`. Deleting an event with bookings returns
`VALIDATION_FAILED`; otherwise delete its seats and event atomically. Capacity and
event validation must be enforced server-side, not solely by JavaFX forms.

SeatSummary: `seatId,eventId,seatNumber,status`; status is `AVAILABLE` or `BOOKED`.
BookingResult retains existing fields:
`bookingId,bookingReference,eventId,seatId,status,createdAt`; status is `CONFIRMED`.
Another user's booking and a missing booking both return `BOOKING_NOT_FOUND`.
Booking a started event returns `VALIDATION_FAILED`. Booking must coordinate with
event edits/deletion so these rules remain true under concurrency (M2/M3 integration).

## Error catalogue and service mapping

Use the existing `ErrorCode` enum:

| Codes | Meaning |
| --- | --- |
| `FRAME_TOO_LARGE`, `MALFORMED_JSON` | Transport/JSON decoding failure |
| `INVALID_REQUEST`, `UNKNOWN_ACTION` | Invalid envelope/IDs/pagination; unsupported operation |
| `VALIDATION_FAILED` | Domain input or business-rule violation |
| `DUPLICATE_EMAIL`, `INVALID_CREDENTIALS` | Registration conflict; login failure |
| `UNAUTHENTICATED`, `SESSION_EXPIRED`, `FORBIDDEN` | Missing valid session; known expiry; insufficient privilege |
| `EVENT_NOT_FOUND`, `SEAT_NOT_FOUND`, `BOOKING_NOT_FOUND` | Requested resource unavailable in its permitted scope |
| `SEAT_ALREADY_BOOKED` | Reservation lost or seat already allocated |
| `SERVER_OVERLOAD`, `INTERNAL_ERROR` | Capacity rejection; unexpected server failure |

M3 maps `BookingException.SEAT_UNAVAILABLE` to wire `SEAT_ALREADY_BOOKED`. Other
booking exception names map directly. Translate only a verified duplicate-seat
constraint violation to that conflict code; a foreign-key error or reference
collision is not a seat conflict. Let the service transaction roll back before the
handler sends its error response.

## Examples

Illustrative values only. Each code block is one frame followed by LF on the wire.

```json
{"requestId":"11111111-1111-4111-8111-111111111111","type":"LOGIN","payload":{"email":"student@example.com","password":"example-password"}}
```
```json
{"requestId":"11111111-1111-4111-8111-111111111111","status":"SUCCESS","data":{"token":"example-session-token","userId":7,"name":"Student","email":"student@example.com","role":"STUDENT"}}
```
```json
{"requestId":"22222222-2222-4222-8222-222222222222","type":"BOOK_SEAT","token":"example-session-token","payload":{"eventId":10,"seatId":5}}
```
```json
{"requestId":"22222222-2222-4222-8222-222222222222","status":"SUCCESS","data":{"bookingId":101,"bookingReference":"UE-550E8400-E29B-41D4-A716-446655440000","eventId":10,"seatId":5,"status":"CONFIRMED","createdAt":"2026-11-19T08:00:00Z"}}
```
```json
{"requestId":"22222222-2222-4222-8222-222222222222","status":"ERROR","errorCode":"SEAT_ALREADY_BOOKED","errorMessage":"Seat is already booked."}
```
The last two frames are alternative outcomes, never two replies to one request.

## Timeouts, reconnect and uncertain outcomes

Client request timeout defaults to 10 seconds; server socket idle timeout defaults
to 30 seconds. Neither timeout proves a write was rolled back. Request IDs provide
correlation only; durable idempotency/replay is not implemented.
Reconnect must not automatically replay mutations. Validate the session, then
refresh seats and the user's history to resolve an uncertain booking result. If
still unresolved, show an unknown-outcome state and allow explicit refresh. A
later explicit retry may return a seat conflict for a booking already owned by
the caller; display the original booking when history confirms ownership.
Read-only requests may be retried with new IDs after connection/session recovery.

## UDP handoff target (M3/M4)

M3 emits internal `BookingCommitted(bookingId,eventId,seatId,committedAt)` only
after successful transaction commit. M4 sends a compact UTF-8 JSON datagram:
`{type:"SEAT_CHANGED",subscriptionId,streamId,eventId,seatId,seatStatus:"BOOKED",sequence,timestamp}`.
No user identity, password or session token is sent in notifications. Datagrams
must fit 1,200 bytes. UDP is a hint; TCP snapshots are authoritative.

Subscription uses authenticated TCP, the connection's peer address, and a validated
client receive port (1–65535); do not accept an arbitrary target host. Default TTL
is 60 seconds; renew by SUBSCRIBE_EVENT every 30 seconds, retaining the same
subscription ID for the same session/event/endpoint. Explicit unsubscribe is
idempotent for its owning session. Expiry/logout removes subscriptions.
Client binds its listener before subscribing. Stream ID changes after server restart;
sequence is monotonically increasing per event within that stream, starting at 1.
M4 serializes sequence assignment/send scheduling per event. Reordering/loss remain
possible; ignore stale/duplicate sequence numbers and coalesce TCP refreshes.
Refresh after notifications, subscription, reconnect, stream change or sequence gap,
and periodically (15 seconds) while viewing an event to recover a lost final packet.
During paginated refresh, a notification triggers another refresh after completion;
pages are not a transactionally consistent snapshot. Close listeners on screen exit.
UDP delivery failure is logged and must not reverse the booking or return a booking
failure. Initial target is a single server on a reachable demo LAN; multi-server
notification coordination and NAT traversal are not implemented guarantees.

## Known implementation gaps

- Step 3 enforces fresh token validation for every registered operation except
  PING, ECHO, REGISTER and LOGIN. Admin registration independently verifies the
  token. Dispatcher/login/registration internal-error responses are sanitized.
- M1/M2/M5: Step 2 application lifecycle, concrete socket handler and bounded
  execution-manager wiring passed the selected 86-test regression run; see
  [verification](TCP_STEP_2.md). Full desktop/PostgreSQL verification remains pending.
- Step 7 mounts the event browser, paginated seat grid, booking/history and role-gated
  administration in JavaFX. It renews UDP subscriptions and reconciles seats over TCP.
  See [desktop status](DESKTOP_STEP_7.md).
- Step 3 replaces the metrics-specific envelope with `ResponseEnvelope`, registers
  GET_METRICS and applies ADMIN authorization to TCP and HTTP monitoring.
- M5: retain the fields of `ServerMetricsSnapshot`; `totalCompletedTasks` counts
  handler tasks, not requests. Add distinct request counters in a reviewed extension.

Each owner adds contract tests with their implementation. Review changes with
consumers before modifying fields, error meanings, authentication or framing.
