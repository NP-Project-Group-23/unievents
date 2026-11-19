# Member 5: Phase 2 ClientHandler Execution, Connection Management & Live Monitoring (Tasks B17 & F07)

> Integration update: [Step 3](AUTH_METRICS_STEP_3.md) replaces the standalone
> `handle(requestId)` metrics API with the shared `RequestHandler` interface,
> registers GET_METRICS, and requires admin access for HTTP and TCP monitoring.
> The earlier method signatures and registration TODOs below are historical.

## 1. Network Programming Concept: Client Execution & Observable Metrics

In Phase 1 (Task B16), Member 5 established the bounded `ServerThreadPoolExecutor` concurrency foundation. Phase 2 delivers **Task B17** and **Task F07**:
- **B17**: Connects the server socket acceptor (Member 01) to Member 02's `ClientHandler` tasks, executing connections through the bounded executor, enforcing backpressure, and exposing live connection, worker, queue, and completed request metrics.
- **F07**: Provides the network monitoring screen populated by authentic, live server metrics and event logs, without fabricated or simulated values.

---

## 2. Architecture & Components

```
Client TCP Socket (Member 01 Acceptor)
              │
              ▼
┌──────────────────────────────────────────────┐
│ ClientExecutionManager (Member 05)           │
│  - registers connection in ConnectionManager │
│  - wraps ClientHandler with cleanup hook     │
└──────────────────────┬───────────────────────┘
                       │ submits to bounded executor
                       ▼
┌──────────────────────────────────────────────┐
│ ServerThreadPoolExecutor (Member 05 - B16)   │
│  - bounded queue & worker pool               │
└──────────────┬───────────────────────────────┘
               │ If pool & queue are saturated
               ▼
┌──────────────────────────────────────────────┐
│ ServerRejectedExecutionHandler               │
│  - throws ServerOverloadException            │
│  - ClientExecutionManager catches exception  │
│  - invokes handler.onOverload(ex)            │
│  - sends structured SERVER_OVERLOAD response │
│  - unregisters connection without leaking    │
│  - acceptor loop remains non-blocked & alive │
└──────────────────────────────────────────────┘
```

### New Backend Components

1. **`ClientConnection`** (`com.unievents.unievents_backend.network.session`):
   - Represents an active TCP socket or network session.
   - Holds a unique `connectionId` (`conn-{uuid}`), remote address, creation timestamp, and active flag.
   - Implements `Closeable` to safely release socket streams.
2. **`ClientHandler`** (`com.unievents.unievents_backend.network.session`):
   - Interface defining Member 02's per-connection `Runnable` and `AutoCloseable` task.
   - Includes `onOverload(ServerOverloadException ex)` default hook to notify the rejected client before closing.
3. **`ClientConnectionManager`** (`com.unievents.unievents_backend.network.executor`):
   - Thread-safe registry (`ConcurrentHashMap<String, ClientConnection>`) tracking live client sessions.
   - Hooks into `ServerMetricsService.trackConnection()` and `releaseConnection()`.
   - Records genuine connection lifecycle events (`CONNECTION_OPENED`, `CONNECTION_CLOSED`).
   - Ensures all active connections are cleanly terminated on server shutdown (`@PreDestroy`).
4. **`ClientExecutionManager`** (`com.unievents.unievents_backend.network.executor`):
   - Bridges the acceptor loop to `ClientHandler` execution.
   - Catches `ServerOverloadException` to apply backpressure, notifies the handler, records `OVERLOAD_REJECTED` in the event buffer, and unregisters the connection without crashing the server socket accept loop.
5. **`ServerEventLogBuffer`** (`com.unievents.unievents_backend.network.executor`):
   - Bounded, thread-safe circular ring buffer (100 events) recording genuine operational events (`CONNECTION_OPENED`, `CONNECTION_CLOSED`, `TASK_QUEUED`, `TASK_EXECUTED`, `OVERLOAD_REJECTED`).
   - Powers the live event stream in the F07 monitoring dashboard.
6. **`MetricsProtocolHandler`** (`com.unievents.unievents_backend.network.executor`):
   - Implements the team's agreed TCP `GET_METRICS` operation.
   - Produces structured protocol response envelopes containing the `ServerMetricsSnapshot`.
7. **`ServerMetricsController`** updates:
   - Exposes `/api/admin/metrics`, `/api/admin/metrics/dashboard`, and `/api/admin/metrics/events`.

---

## 3. Frontend Monitoring Dashboard (Task F07)

Located at `frontend/src/app/monitoring/page.tsx` (and `frontend/src/app/admin/metrics/page.tsx`):
- **Worker Thread Meter:** Active threads vs Max pool size with percentage utilization.
- **Queue Backpressure Meter:** Queued tasks vs Total queue capacity with overload indicators.
- **Throughput Counters:** Submitted, Completed, and Rejected (overload) task counts.
- **Client Latency & Health:** Active client connections, average task duration (ms), peak duration (ms), and server status (`RUNNING` / `SHUTTING_DOWN`).
- **Live Event Stream:** Chronological feed of genuine server operational events from `ServerEventLogBuffer`.
- **Auto-Refresh:** Configurable live polling (2s interval) with manual pause and instant refresh.
- Accessible directly from the UniEvents portal home page.

---

## 4. Teammate Integration Contracts

| Teammate | Contract / Method | Responsibilities |
| --- | --- | --- |
| **Member 01 (TCP Acceptor)** | `ClientConnection.fromSocket(socket)`, `ClientExecutionManager.submitClient(handler)` | When `serverSocket.accept()` returns a client socket, wrap it in a `ClientConnection`, instantiate Member 02's `ClientHandler`, and pass it to `submitClient(...)`. The method safely handles overload rejections without terminating the accept loop. |
| **Member 02 (ClientHandler)** | Implement `ClientHandler` interface (`network/session/ClientHandler.java`) | Implement the `run()` loop and `close()` cleanup. Override `onOverload(ex)` if custom TCP protocol packets need to be written before socket closure. |
| **Member 01 (TCP Dispatcher)** | `MetricsProtocolHandler.handle(requestId)` | Register against the `GET_METRICS` request type in the TCP request router. |
| **Member 05 (Monitoring UI)** | `GET /api/admin/metrics/dashboard` | Consumed by the monitoring UI to display genuine server counters and events. |

---

## 5. Verification & Test Evidence

### Test Suite (`src/test/java/com/unievents/unievents_backend/network/executor/`)

1. **`ClientExecutionManagerTests`**:
   - `successfulClientExecutionAndCleanup`: Submits a client handler, verifies connection registration, execution in worker pool, and automatic cleanup on completion.
   - `overloadHandledPredictably`: Saturates pool (1) + queue (1). A 3rd client triggers `onOverload()`, returns `false`, logs `OVERLOAD_REJECTED`, and unregisters without throwing an unhandled exception to the acceptor.
2. **`ClientConnectionManagerTests`**:
   - `connectionLifecycle`: Validates connection registration, lookup, active count, and metrics service notifications.
   - `closeAllConnections`: Validates clean termination of all active connections on shutdown.
3. **`MetricsProtocolHandlerTests`**:
   - `handlesGetMetrics`: Validates `GET_METRICS` request handling and response envelope format.
   - `generatesFallbackRequestId`: Ensures missing request IDs are replaced by non-null correlation IDs.
4. **`ServerEventLogBufferTests`**:
   - `recordsAndBoundsEvents`: Validates newest-first order and capacity truncation.
5. **`ServerMetricsControllerTests`**:
   - Validates `/api/admin/metrics`, `/events`, and `/dashboard` REST endpoints.

### Run Tests Command

```powershell
cd backend
.\mvnw.cmd test -Dtest=ClientExecutionManagerTests,ClientConnectionManagerTests,MetricsProtocolHandlerTests,ServerEventLogBufferTests,ServerMetricsControllerTests,ServerThreadPoolExecutorTests,ServerMetricsServiceTests,ExecutorPropertiesTests
```
