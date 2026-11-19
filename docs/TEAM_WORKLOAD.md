# UniEvents team workload guide

> Current integration baseline (2026-11-19): use [ARCHITECTURE.md](ARCHITECTURE.md)
> and [PROTOCOL.md](PROTOCOL.md). The supplied full workload plan selects JavaFX
> clients with TCP/UDP. The browser gateway proposal and conflicting ownership or
> framing suggestions below are historical; consult the new contracts for new work.

This proposed plan follows `NP project initial idea.docx`: five members, five network programming concepts, student accounts, event management, seat booking, and real-time updates. Names and deadlines were not supplied; replace M1–M5 with actual names and agree dates before starting. The folders and interfaces below are proposed work, not implemented functionality.

## 1. Scope and working agreement

Deliver registration/login, student account details, event browsing, an admin event interface, visual seat selection, booking confirmation/history, and updates to other viewers. Demonstrate raw TCP, UDP, multithreading, synchronization, and ExecutorService. Exclude payments, QR scanning, email, SMS, and recommendations from this release.

Each issue has one implementer, one reviewer, acceptance criteria, affected paths, dependencies, and a small PR. Ownership means coordinating changes and reviewing them; it does not forbid teammates from contributing. No one should wait until the last week to integrate.

## 2. Architecture to agree before parallel implementation

Browsers do not expose general raw TCP/UDP sockets. Use a Java gateway to connect the Next.js UI to the network programming implementation. Confirm this approach with the lecturer before building it; a separate Java demonstration client can also show direct socket communication.

```text
Next.js browser
  | HTTPS/HTTP requests                 ^ WebSocket seat-change messages
  v                                    |
Spring Boot gateway: controllers, Java TCP client, UDP listener, WS bridge
  | framed TCP requests                ^ UDP notifications
  v                                    |
Java TCP server -> bounded executor -> client handler -> domain services
                                                       | transactions
                                                       v
                                                   PostgreSQL
```

The gateway and TCP server may initially run in one JVM with different ports, but requests should actually cross the socket boundary to demonstrate TCP. Use configurable, distinct HTTP/TCP/UDP ports; agree them in the integration issue. Avoid controllers that bypass the socket path for the operations being demonstrated. Do not put blocking socket reads on a UI or WebSocket event thread.

M1 owns TCP acceptance and transport. M5 owns the executor implementation injected into the server. M2 owns the per-connection handler submitted to that executor. They must not each build a different server loop. UDP updates are hints: the database and reliable seat queries remain authoritative.

## 3. Member ownership and deliverables

Java paths below are relative to `backend/src/main/java/com/unievents/unievents_backend/`. Frontend paths are relative to `frontend/src/`. Put corresponding tests under the matching backend test package.

| Member / concept | Primary paths | Feature work and deliverables | Reviewer |
| --- | --- | --- | --- |
| M1: TCP sockets | `network/tcp/`, `protocol/`, `auth/`, `gateway/auth/`; frontend `app/(auth)/`, `features/auth/` | TCP accept loop and client, framing/codec, request correlation, timeouts; register/login/account backend and UI; protocol document and round-trip tests | M2; M3 for authentication |
| M2: multithreading | `network/session/`, `event/`, `gateway/event/`; frontend `app/events/`, `features/events/` | ClientHandler and session lifecycle; isolated concurrent connections; event listing/detail and seats query integration; concurrent-client evidence | M1; M5 for executor integration |
| M3: synchronization | `booking/`, `seat/`, `gateway/booking/`, resources `db/migration/`; frontend `app/bookings/`, `features/booking/` | Atomic reservation, seat entities, database constraints/migrations, booking reference/history; seat selection and booking feedback; race tests | M2; M4 for notification boundary |
| M4: UDP | `network/udp/`, `realtime/`, `gateway/realtime/`; frontend `features/realtime/` | Subscription lifecycle, UDP sender/receiver, WebSocket bridge, reconnect/resync logic and update hook; dropped/duplicate/out-of-order packet tests | M1; M3 for committed-seat events |
| M5: ExecutorService | `network/executor/`, `config/`, `gateway/admin/`; frontend `app/admin/`, `features/admin/`, `components/ui/`, `lib/` | Bounded worker pool, rejection/backpressure and shutdown; admin UI/API adapters, shared UI/API client, CI and integration coordination | M2; M1 for gateway transport |

M2 owns the event service called by M5's admin adapter. M3 owns seat/booking persistence; M1 and M2 supply their user/event schema requirements. M4 supplies a hook/component contract; M3 inserts it into the seat page so both do not rewrite that page. Each member implements the tests and documentation for their own work. M5 coordinates integration; M5 is not responsible for fixing everyone's modules.

## 4. Shared files: coordinate before editing

| Shared area | Coordinator | Rule |
| --- | --- | --- |
| Protocol DTOs, error codes, `docs/PROTOCOL.md` | M1 | Contract PR first; consumers review before merge |
| `pom.xml`, app entry point, application config | M5 | Separate small dependency/config PR; tell affected owners |
| DB migrations and relationships | M3 | Allocate unique ordered migration IDs; never rewrite applied migrations |
| `package.json`, `package-lock.json` | M5 | One dependency-change PR at a time; commit both together |
| Root layout, global CSS, shared UI and API client | M5 | Feature code uses shared components; avoid global restyling |
| `.github/`, root `.gitignore`, formatting configuration | M5 | Separate tooling PR; all members review workflow changes |
| Feature-specific documentation | Feature owner | Edit own document; coordinator updates common index |

Use existing Java package `com.unievents.unievents_backend`; do not independently rename the base package. Split feature modules into separate files. Do not duplicate shared DTOs across five incompatible implementations.

## 5. Contract checklist and proposed interface

Create a small contract-only PR before feature implementation. The following is a starting proposal, not an existing API:

| Operation | Proposed browser endpoint | TCP operation | Owner |
| --- | --- | --- | --- |
| Register / login | `POST /api/auth/register`, `POST /api/auth/login` | `REGISTER`, `LOGIN` | M1 |
| Account view/update | `GET/PATCH /api/me` | `GET_ACCOUNT`, `UPDATE_ACCOUNT` | M1 |
| Event list / detail | `GET /api/events`, `GET /api/events/{id}` | `LIST_EVENTS`, `GET_EVENT` | M2 |
| Seat snapshot | `GET /api/events/{id}/seats` | `GET_SEATS` | M2 + M3 |
| Reserve one seat | `POST /api/bookings` | `BOOK_SEAT` | M3 |
| Personal history / confirmation | `GET /api/me/bookings`, `GET /api/bookings/{id}` | `MY_BOOKINGS`, `GET_BOOKING` | M3 |
| Admin create/update/bookings | `POST /api/admin/events`, `PATCH /api/admin/events/{id}`, `GET /api/admin/events/{id}/bookings` | `CREATE_EVENT`, `UPDATE_EVENT`, `EVENT_BOOKINGS` | M5 + M2/M3 |
| Event updates | proposed WebSocket `/ws/events` | UDP `SEAT_CHANGED` to gateway | M4 |

Agree before coding:

- TCP envelope fields: version, request ID, operation, authenticated context, payload; response request ID, success, data or stable error code.
- Frame messages explicitly, for example a bounded length prefix followed by UTF-8 JSON. TCP reads can contain partial or multiple messages; never assume one read equals one request. Reject oversized/invalid frames.
- Define UUID/string ID representation, timestamps with timezone, seat states, validation errors, and frontend TypeScript types matching DTOs.
- Define authentication storage/expiry, password hashing, admin authorization and how TCP authenticates the caller. Never trust a student ID or admin flag supplied by the browser.
- Decide request timeout/retry and idempotency rules. A timeout after a booking commit must not create a second booking on retry. Specify how to retrieve the original result.
- Define `SEAT_UNAVAILABLE` behavior (browser HTTP 409), unauthorized/forbidden responses, and server-busy handling.
- UDP payload: version, event ID, seat ID, committed status and event sequence/version. No passwords or student information. Bound payload size and authenticate/validate subscriptions and notification sources.
- Each gateway instance maintains browser subscriptions and receives the relevant UDP updates. Specify expiry, unsubscribe, reconnect and reliable resync.

## 6. Critical correctness rules

Booking is a transaction: validate event/seat/user, perform an atomic reservation, persist booking, commit, then notify. M3 selects and documents the lock/transaction strategy. A Java `synchronized` block only coordinates cooperating threads in that JVM; retain a database uniqueness/atomic-update safeguard for event-seat allocation. Multiple-seat booking is optional; if added, define all-or-nothing behavior and deterministic lock ordering.

M4 must tolerate UDP loss, duplication and reordering. Use snapshots on initial load/reconnect and periodic or gap-triggered resync. A missed final packet may not create a detectable sequence gap, so gap detection alone is insufficient. Failed notifications must not roll back a successful reservation. Disconnection/retry UX must query the actual booking result.

M5 should bound the executor queue and worker count, reject overload predictably and shut down cleanly. M2 closes sockets and session/subscription resources on error, timeout and disconnect. Test slow/idle clients as well as fast clients.

## 7. Milestones and merge order

Use these as relative milestones; set actual dates together based on the submission deadline.

| Phase | Work in parallel | Exit gate / merge order |
| --- | --- | --- |
| 0: shared baseline | Everyone reads guides; M5 checks setup and branch rules; M1 drafts protocol; M3 drafts schema | Backend + frontend baseline merged into `dev`; everyone can build; approved contracts and assigned issues |
| 1: infrastructure | M1 TCP codec/client/server; M2 handler/session; M5 executor; M3 reservation prototype; M4 UDP prototype | Contracts first, then executor/handler interfaces, then TCP integration; socket request gets a real response |
| 2: vertical features | M1 auth; M2 events; M3 booking; M4 bridge; M5 shared UI/admin | Auth/events/schema before booking integration; one browser can reserve a seat through TCP |
| 3: realtime and integration | M4 updates; M3 UI hook integration; M2 load tests; M1 auth/error tests; M5 overload/admin integration | Two browser sessions see consistent seat state; one winner under contention; admin rules hold |
| 4: stabilization/demo | All fix owned bugs, record evidence, review documentation | Clean clone passes documented setup; final checks pass; approved `dev` → `main` release PR |

Use stubs behind agreed interfaces while dependencies are pending. Do not merge fake success paths as completed features. Dependent PRs should identify the prerequisite and normally wait until it reaches `dev`, then merge `origin/dev` into the feature branch and retest.

## 8. Acceptance evidence by member

| Member | Minimum evidence |
| --- | --- |
| M1 | TCP round trip; split/coalesced frames; malformed/oversized messages; disconnect/timeout; registration/login and authorization failures |
| M2 | Multiple simultaneous clients with separate sessions; slow client does not block all clients; cleanup after abrupt disconnect; event queries |
| M3 | Concurrent independent requests for the same seat produce exactly one committed booking and one persisted allocation; different seats can succeed; retry/idempotency; personal-history authorization |
| M4 | Two subscribed viewers update after commit; lost/duplicate/out-of-order UDP handling; reconnect and reliable resync; no updates for an unrelated event |
| M5 | Configured worker/queue bounds under overload; rejection is observable; graceful shutdown; admin permission checks; reproducible application build |

Record client count, configuration, expected/actual result, commands and relevant logs in `docs/testing/`. Do not claim a test passed without running it. Every PR includes its own evidence; integration checks run again after merging dependencies.

Final demo: admin creates an event; two students log in; both select the same seat; issue concurrent bookings; show one confirmation and one unavailable response; show both views converge; show persisted booking/history and TCP/UDP/thread-pool evidence. Also show recovery after a disconnected viewer reconnects.

## 9. Team routine and definition of done

Before starting, post the issue, branch and paths being changed in the team's agreed channel. Each workday report completed work, next work, blockers and shared-file changes. Ask for review early with a draft PR. Reviewer responds within an agreed working-day window; use a backup reviewer if unavailable.

A task is done when acceptance criteria pass, interfaces and setup docs are updated, no secrets/generated output are tracked, a teammate reviewed the diff, conflicts are resolved with affected owners, and the PR is merged into `dev`. Successful compilation alone does not establish booking or networking correctness.
