# Integration architecture and configuration

Implementation status: [Step 4](EVENTS_STEP_4.md) adds event operations, seat
generation, Flyway configuration and event foreign keys. The migration handoff
below describes ownership; use the Step 4 guide for current executable setup and
existing-schema adoption. Shared database deployment is still pending.

Baseline selected for the current implementation plan, 2026-11-19: the supplied
UniTicket workload document's JavaFX desktop architecture. Existing web login and
monitoring remain available, but do not replace the desktop/TCP/UDP demonstration.
This document and [PROTOCOL.md](PROTOCOL.md) supersede contradictory architecture
proposals in TEAM_WORKLOAD.md and PROJECT.md for upcoming integration work.

```text
JavaFX application
  -> TcpClient -> TCP ServerSocket acceptor (M1)
     -> concrete ClientHandler (M2)
     -> ClientExecutionManager / bounded ExecutorService (M5)
        executes handler -> RequestDispatcher (M1)
        -> auth (M1), events (M2), seats/bookings (M3), metrics (M5)
        -> repositories -> Neon PostgreSQL
Booking commit -> BookingCommitted (M3) -> UDP sender (M4)
  -> JavaFX UDP listener -> authoritative GET_SEATS over TCP
```

Clients never receive database credentials or connect directly to PostgreSQL.
Use the existing Java 21 backend and package root; retain existing protocol,
client, service and executor classes. No separate acceptor or worker pool per member.
The Spring context owns server components. The desktop client has its own entry
point and must not start the server Spring context or load database configuration.
M5 will separate client/shared/server build artifacts during packaging if needed;
this contract does not restructure Maven or enable unimplemented startup commands.

## Ownership and handoffs

| Owner | Produces | Consumer / rule |
| --- | --- | --- |
| M1 | Acceptor, codec, dispatcher, authenticated context, TcpClient | M2 uses codec/dispatcher; all protected handlers use fresh verified identity |
| M2 | Concrete implementation of existing `network.session.ClientHandler` | M1 creates it per accepted socket; M5 executes it |
| M5 | `ClientExecutionManager.submitClient(handler)` | Sole production submission path; owns tracking/rejection and bounded executor |
| M2 | Event service and transactional seat generation | M3 consumes event validity; M5 coordinates schema application |
| M3 | Seat/booking/history handlers and after-commit event | M4 updates viewers; M5 renders history; M3 supplies selection/action logic |
| M4 | Seat grid, UDP subscription/listener, reconciliation | M3 wires book action into grid; M5 mounts it in the desktop shell |
| M5 | JavaFX launcher/navigation, metrics, build/config/startup | M1 supplies auth views; M2 supplies event/admin views; each owner tests their feature |

One connection occupies a handler worker until disconnect/idle timeout. Keep the
acceptor free from request processing and database work. Admission queues and
timeouts must be tested together: queued clients may exceed their request timeout.
Reject and close overloaded connections predictably; do not create fallback workers.
Track requests independently from long-lived connection tasks.

Shutdown order: stop accepting, stop notification/subscription scheduling, close
active and queued sockets, await workers for configured grace time, then force
remaining work to stop. Executor/connection managers own cleanup exactly once.
UI network work runs off the JavaFX application thread; apply scene changes through
the existing `FxThreadUtils`/JavaFX thread mechanism.

## Configuration contract

Names marked planned are not yet read by runtime code. M5/M1 implement binding
in the next integration step; this table is not a claim that setting them works now.

| Setting | Default | Status / owner |
| --- | --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Private environment values | Existing JDBC settings; M5 |
| `DB_MIGRATION_URL` | No default; direct isolated endpoint for verification | Explicit migrate profile/Maven plugin implemented; M5 |
| `TCP_ENABLED` | `true` | Server lifecycle switch; implemented in Step 2 |
| `TCP_BIND_ADDRESS` | `127.0.0.1` | Server binding implemented; explicitly set LAN bind for demo |
| `TCP_PORT` | `9090` | Server binding implemented; desktop configuration binding pending |
| `TCP_HOST` | `127.0.0.1` | Planned desktop destination; M1/M5 |
| `TCP_REQUEST_TIMEOUT_MS` | `10000` | Existing client constant; planned configuration binding |
| `TCP_IDLE_TIMEOUT_MS` | `30000` | Server configuration binding implemented |
| `UDP_CLIENT_PORT` | `0` (OS-selected local port) | Client listener implemented; subscribe with actual bound port; M4 |
| `UDP_SUBSCRIPTION_TTL_SECONDS` | `60` | Server subscription TTL implemented; M4 |
| `SEAT_REFRESH_SECONDS` | `15` | Planned; M4 |
| `EXECUTOR_CORE_POOL_SIZE` | `10` | Existing; M5 |
| `EXECUTOR_MAX_POOL_SIZE` | `20` | Existing; M5 |
| `EXECUTOR_QUEUE_CAPACITY` | `50` | Existing; M5 |
| `EXECUTOR_KEEP_ALIVE_SECONDS` | `60` | Existing; M5 |
| `EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS` | `10` | Existing; M5 |

HTTP uses existing port 8080 and the optional web client port 3000. UDP subscriptions
are registered over TCP, so no public UDP registration server is required. Server
sender may bind an OS-selected local port. Multiple local JavaFX clients use distinct
OS-selected receive ports. Configure host/ports explicitly for remote demo clients.

## Database and migration handoff

Keep `app_users`, not a duplicate `users` table. Existing seat/booking BIGINT IDs,
status strings, unique seat allocation and booking references remain compatible.
M2 introduces events with creator FK, title, description, venue, start time, capacity
and creation time, following the field contract in PROTOCOL.md. M3 adds event FKs
to seats and bookings and preserves the composite booking/seat event relationship.
M2 owns safe deletion and immutable capacity; M3 owns booking correctness.

M5 reconciles existing `schema.sql` initialization with versioned migration startup.
Before selecting a baseline or migration numbers, inspect the target schema and
migration history. Preserve any applied V1 migration. Do not enable blanket
baseline-on-migrate or rewrite existing data to fit a guessed baseline. Existing
role upgrades need constraints checked as well as a column added. Verify the fresh
install and existing-schema upgrade paths on an isolated Neon branch before shared
deployment. No database migration is applied by this contract-only step.

## Immediate integration backlog

1. M1/M2/M5: wire TCP lifecycle and concrete handler to the existing bounded executor.
2. M1/M5: enforce session/admin rules, adapt/register metrics and test real counters.
3. M2/M3/M5: event module, schema integration and migration execution.
4. M3: booking handlers and after-commit event; M5: desktop navigation/history.
5. M4: subscription/UDP/grid/recovery with M3's booking action.
6. All: PostgreSQL/TCP races, reconnect, overload, packet loss and packaged demo.

Contract completion means a concrete shared specification exists, not that runtime
gaps are fixed. Owner/consumer review and implementation evidence remain required.
