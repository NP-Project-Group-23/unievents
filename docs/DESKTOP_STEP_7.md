# Step 7: JavaFX desktop integration

## Implemented

Successful login now replaces the authentication card with a role-aware desktop
dashboard. Students can browse events, select an event, page through its seats,
book an available seat and review their private booking history. Seat buttons are
derived only from `GET_SEATS`; a successful booking refreshes both the current page
and history.

Selecting an event binds the UDP listener before sending `SUBSCRIBE_EVENT`. The
desktop renews the subscription every 30 seconds, refreshes seats after every valid
UDP hint, and reconciles through `GET_SEATS` every 15 seconds. Changing events or
logging out cancels timers, unsubscribes and closes the UDP socket. UDP never mutates
the displayed state directly.

ADMIN sessions receive an additional tab for creating, loading, updating and
deleting events. Update deliberately omits capacity because capacity is immutable.
All requests use the active session token and the same asynchronous `TcpClient`.
The standalone `UniEventsDesktopApplication` reads `TCP_HOST` and `TCP_PORT`, owns
the TCP client lifecycle, and opens the JavaFX scene.

## Verification

`DesktopDataControllerTests` verifies authenticated request construction, event and
booking payloads, immutable-capacity updates, UDP registration, seat-page refresh,
unsubscribe/cleanup, missing-session rejection, login navigation and role-gated tabs.
The existing authentication UI suite remains part of the focused regression run.

Verification on 2026-11-19: the focused desktop suites passed 12/12 tests, and the
complete backend suite passed 140 tests with 0 failures, 0 errors and 0 skipped.

## Remaining work

The desktop requires a manual visual demo on the target machines and network,
including firewall/UDP behavior and accessibility review. Packaged distribution,
automatic reconnect/session recovery, shared PostgreSQL race verification and the
final multi-client acceptance demonstration remain pending.
