# Step 6: authenticated UDP seat-change hints

## Implemented

`SUBSCRIBE_EVENT` and `UNSUBSCRIBE_EVENT` are registered on the shared TCP
dispatcher and require a valid session. The server takes the target IP address
from the authenticated TCP connection and accepts only a validated UDP port; a
payload host is ignored. A missing event returns `EVENT_NOT_FOUND`.

Subscriptions expire after `UDP_SUBSCRIPTION_TTL_SECONDS` (default 60), and an
identical session/event/address/port request renews the existing subscription ID.
They are removed by unsubscribe, logout, session expiry or server shutdown.
Another session cannot remove a subscription it does not own.

`UdpSubscriptionManager` consumes the identity-free `BookingCommitted` event on
a dedicated worker. It assigns a monotonic sequence per event and sends a compact
`SEAT_CHANGED` datagram no larger than 1,200 bytes. Datagram contents do not include
the student ID, email or session token. Delivery failures are logged and cannot
alter the committed booking or its TCP response.

The reusable `UdpSeatChangeListener` binds before subscription, filters packets by
subscription ID, rejects oversized or invalid input, ignores stale/duplicate
sequences, detects sequence gaps and stream changes, and emits a refresh signal.
The signal is deliberately not a seat mutation: the consumer must issue
`GET_SEATS`, because TCP/database state remains authoritative.

## Verification

Focused tests use real TCP and UDP sockets and cover renewal, peer-derived routing,
identity-free payloads, sequencing, ownership, logout, TTL expiry, validation,
unsubscribe, packet filtering, duplicates, gaps and stream changes.

Verification on 2026-11-19: the focused UDP suites passed 4/4 tests, and the
complete backend suite passed 136 tests with 0 failures, 0 errors and 0 skipped.

## Remaining work

The JavaFX event browser and seat grid still need to mount the listener, subscribe
when entering an event, renew every 30 seconds, request `GET_SEATS` after each hint,
refresh every 15 seconds, and close/unsubscribe on screen exit. Shared PostgreSQL
multi-client verification and deployment also remain pending.
