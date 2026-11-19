# Step 2: TCP lifecycle and bounded execution

Spring now creates `TcpServer` using the shared `ClientExecutionManager` and starts
it through `SmartLifecycle`. Accepted sockets are processed by `SocketClientHandler`,
the concrete implementation of the existing `ClientHandler` interface. The original
frame/dispatch loop was moved out of the acceptor, preserving current auth behavior.
Session enforcement and metrics protocol registration remain Step 3 work.

Production connections pass through the existing bounded pool, connection registry
and metrics. Standalone constructors remain for transport tests; their default pool
is now bounded (10 core, 20 maximum, queue 50) instead of a cached thread pool.
Injected standalone executors remain caller-owned.

The listener stops before Spring bean destruction shuts down the executor. Active
and queued sockets are closed, connection metrics are decremented through the same
unregister path, and the existing executor shutdown timeout applies. Overloaded
production clients receive `SERVER_OVERLOAD` and close. Connection task counters
are not request counters.

## Startup

Run `backend/mvnw.cmd -f backend/pom.xml spring-boot:run` from the repository root
with the existing database environment configured. This starts the HTTP application
and the TCP listener. TCP defaults to loopback port 9090 and 30 seconds idle timeout.
Set `TCP_BIND_ADDRESS`, `TCP_PORT`, `TCP_IDLE_TIMEOUT_MS` to override; set
`TCP_ENABLED=false` to disable the listener. Port 0 selects an ephemeral test port.
Occupied ports fail startup instead of silently disabling TCP. Binding does not
provide transport encryption; use the intended local/trusted demo network.

## Verification status

Added `TcpLifecycleTests` to start a minimal Spring context with real sockets:

- Automatic startup and correlated PING while another client is idle.
- One worker plus one queue slot; the third connection is rejected with overload.
- Context shutdown closes active and queued sockets, terminates the pool, and
  resets connection counts.
- Idle timeout releases its socket and worker.

Test resource configuration uses an isolated H2 default database and ephemeral TCP
ports, avoiding shared database access and port collisions between test contexts.
Tests with explicit datasource properties retain their individual fixtures.

Verified on 2026-11-19: BUILD SUCCESS; 86 tests run, 0 failures, 0 errors,
0 skipped. All three new lifecycle tests passed, along with the selected transport,
executor, authentication, booking and application-context regression tests.
The initial test compilation error was corrected before this successful run.

Run from `backend`:

```powershell
.\mvnw.cmd '-Dtest=TcpLifecycleTests,TcpServerClientTest,FrameCodecTest,RequestDispatcherTest,AuthHandlersIntegrationTest,T01ComprehensiveEdgeCaseTest,*Tests' test
```

This includes existing executor, authentication, booking and concurrency regressions.
It does not run every test class: JavaFX UI tests and other singular `*Test` classes
outside the explicit list are not included. PostgreSQL and desktop end-to-end
verification remain separate integration gates.
The contract documentation from Step 1 remains in the working tree separately.
