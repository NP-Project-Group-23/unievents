# Member 5: Phase 1 Bounded ExecutorService & Worker Lifecycle (Task B16)

## 1. Network Programming Concept: ExecutorService

In a high-concurrency client-server architecture such as the UniEvents ticket booking system, handling incoming TCP socket connections or HTTP requests by spawning unbounded native threads (`new Thread(...)` or unbounded cached thread pools) leads to:
- Thread exhaustion and severe context-switching latency.
- Out-of-memory errors (`OutOfMemoryError: unable to create new native thread`) during ticket release rushes.
- Inability to apply predictable backpressure to overloaded servers.

Member 5's assigned concept is **ExecutorService**. Phase 1 implements a bounded, instrumented `ServerThreadPoolExecutor` that manages worker lifecycles, enforces backpressure under load, provides safe graceful termination, and exposes real-time server metrics for administrative monitoring.

---

## 2. Architecture & Components

The implementation resides in `backend/src/main/java/com/unievents/unievents_backend/network/executor/`:

```
network/executor/
├── ExecutorProperties.java             # Spring @ConfigurationProperties (unievents.executor.*)
├── NamedThreadFactory.java             # ThreadFactory producing predictable non-daemon worker threads
├── ServerOverloadException.java        # Structured runtime exception carrying backpressure metadata
├── ServerRejectedExecutionHandler.java # Custom RejectedExecutionHandler recording overload metrics
├── ServerMetricsSnapshot.java          # Immutable record for JSON-serializable live server metrics
├── ServerThreadPoolExecutor.java       # Instrumented ThreadPoolExecutor with lifecycle hooks
├── ServerMetricsService.java           # Spring Service managing worker pool & connection tracking
├── ExecutorConfiguration.java          # Spring @Configuration assembling beans
└── ServerMetricsController.java        # REST endpoint GET /api/admin/metrics & /api/metrics
```

### Contention and Backpressure Boundary

```text
Incoming Task / Socket Connection
             │
             ▼
┌───────────────────────────────┐
│ ServerThreadPoolExecutor      │
│  - corePoolSize: 10           │
│  - maxPoolSize: 20            │
└──────────────┬────────────────┘
               │ If pool saturated
               ▼
┌───────────────────────────────┐
│ ArrayBlockingQueue            │
│  - capacity: 50               │
└──────────────┬────────────────┘
               │ If queue full
               ▼
┌───────────────────────────────┐
│ ServerRejectedExecutionHandler│
│  - increments rejected count  │
│  - logs backpressure warning  │
│  - throws ServerOverload-     │
│    Exception (SERVER_OVERLOAD)│
└───────────────────────────────┘
```

1. **`ServerThreadPoolExecutor`**:
   - Extends standard Java `ThreadPoolExecutor`.
   - Utilizes an explicit, bounded `ArrayBlockingQueue<Runnable>(queueCapacity)`.
   - Overrides lifecycle hooks:
     - `beforeExecute(Thread t, Runnable r)`: Captures nano-precision start timestamps, tracks active workers, updates `peakActiveWorkers`.
     - `afterExecute(Runnable r, Throwable t)`: Calculates task execution durations, accumulates total and maximum durations, unwraps `Future<?>` exceptions, and increments `totalCompletedTasks`.
     - `terminated()`: Logs clean termination with final cumulative metrics.
2. **`NamedThreadFactory`**:
   - Assigns predictable names formatted as `unievents-worker-{sequence}`.
   - Enforces non-daemon status so tasks complete reliably without mid-execution JVM halts.
   - Attaches an `UncaughtExceptionHandler` to log unhandled runtime exceptions rather than silently terminating worker threads.
3. **`ServerRejectedExecutionHandler` & `ServerOverloadException`**:
   - When active workers reach `maxPoolSize` and the queue is completely full, the custom rejection handler increments the atomic rejection counter.
   - Throws `ServerOverloadException` with error code `SERVER_OVERLOAD`, carrying snapshot statistics (`activeWorkers`, `maxPoolSize`, `queuedTasks`, `queueCapacity`).
   - Callers can catch this to return a deterministic `SERVER_OVERLOAD` TCP packet or HTTP 503 response.
4. **`ServerMetricsSnapshot`**:
   - Immutable Java record exposing authentic runtime metrics:
     - `activeWorkers`, `currentPoolSize`, `corePoolSize`, `maxPoolSize`
     - `queuedTasks`, `queueRemainingCapacity`
     - `totalSubmittedTasks`, `totalCompletedTasks`, `totalRejectedTasks`
     - `activeConnections` (tracks live TCP/WebSocket client connections)
     - `peakActiveWorkers`, `averageExecutionTimeMs`, `maxExecutionTimeMs`
     - `state` (`RUNNING`, `SHUTTING_DOWN`, `TERMINATED`)
5. **Graceful Shutdown**:
   - Implements a two-phase shutdown via `gracefulShutdown(timeout, unit)`:
     1. Invokes `shutdown()` to reject further tasks while permitting in-flight work to complete.
     2. Awaits termination up to the configured `shutdownTimeoutSeconds`.
     3. If the timeout expires or the thread is interrupted, falls back to `shutdownNow()`.
   - Tied to Spring container lifecycle via `@PreDestroy` in `ServerMetricsService`.

---

## 3. Configuration Reference

The executor is configurable via `application.properties` and environment variables:

| Property | Environment Variable | Default | Description |
| --- | --- | --- | --- |
| `unievents.executor.core-pool-size` | `EXECUTOR_CORE_POOL_SIZE` | `10` | Core number of worker threads |
| `unievents.executor.max-pool-size` | `EXECUTOR_MAX_POOL_SIZE` | `20` | Maximum worker threads allowed |
| `unievents.executor.queue-capacity` | `EXECUTOR_QUEUE_CAPACITY` | `50` | Maximum capacity of the bounded queue |
| `unievents.executor.keep-alive-seconds` | `EXECUTOR_KEEP_ALIVE_SECONDS` | `60` | Keep-alive for idle threads above core |
| `unievents.executor.shutdown-timeout-seconds` | `EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS` | `10` | Graceful shutdown wait timeout |
| `unievents.executor.thread-prefix` | — | `unievents-worker-` | Worker thread naming prefix |

---

## 4. Teammate Integration Contracts

| Teammate | Integration Point | Usage |
| --- | --- | --- |
| **Member 01 (TCP Acceptor)** | `ServerMetricsService.trackConnection()`, `releaseConnection()` | Call upon accepting a new TCP socket and when closing a disconnected socket to maintain accurate connection counts. |
| **Member 02 (ClientHandler)** | `ServerMetricsService.execute(clientHandler)` | Submit `ClientHandler` instances to the executor instead of spawning loose threads. |
| **Member 04 (Realtime/UDP)** | `ServerMetricsService.execute(...)` | Dispatch asynchronous background UDP notification tasks to the worker pool. |
| **Member 05 (Monitoring Screen / F07)** | `GET /api/admin/metrics` or TCP `GET_METRICS` | Fetch `ServerMetricsSnapshot` to render live charts and thread pool metrics in the dashboard. |

---

## 5. Verification & Test Evidence

### Test Suite (`src/test/java/com/unievents/unievents_backend/network/executor/`)

1. **`ServerThreadPoolExecutorTests`**:
   - `workerThreadNamingAndProperties`: Verifies `unievents-worker-*` naming, non-daemon flag, and normal priority.
   - `taskExecutionAndLifecycleTracking`: Validates `beforeExecute`/`afterExecute` timing calculation, submitted/completed counters.
   - `boundedQueueAndOverloadRejection`: Saturated pool (2) + queue (2) = 4 tasks. The 5th task triggers `ServerOverloadException` with `SERVER_OVERLOAD`, incrementing `totalRejectedTasks`.
   - `metricsSnapshotAccuracy`: Validates that active, queued, peak workers, and pool state accurately reflect live concurrency.
   - `gracefulShutdown`: Proves in-flight tasks finish, pool shuts down and transitions to `TERMINATED`.
   - `uncaughtTaskExceptionHandling`: Confirms unhandled task exceptions do not crash worker threads or corrupt the executor.
   - `connectionTracking`: Validates atomic increment, decrement, and zero floor.
2. **`ServerMetricsServiceTests`**:
   - Tests Spring service lifecycle, task submission (`Runnable` and `Callable`), connection tracking, and snapshot reporting.
3. **`ServerMetricsControllerTests`**:
   - Tests `GET /api/admin/metrics` endpoint returning HTTP 200 OK with `ServerMetricsSnapshot` JSON payload.
4. **`ExecutorPropertiesTests`**:
   - Validates configuration defaults, boundary checks (`maxPoolSize >= corePoolSize`), and non-positive value rejections.

### Run Tests Command

```powershell
cd backend
.\mvnw.cmd test -Dtest=ServerThreadPoolExecutorTests,ServerMetricsServiceTests,ServerMetricsControllerTests,ExecutorPropertiesTests
```
