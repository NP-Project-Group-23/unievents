package com.unievents.unievents_backend.network.executor;

import com.unievents.unievents_backend.network.session.ClientConnection;
import com.unievents.unievents_backend.network.session.ClientHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Connects the server socket acceptor (Member 01) to Member 02's {@link ClientHandler}
 * tasks via Member 05's bounded {@link ServerThreadPoolExecutor} (Task B17).
 * <p>
 * Ensures predictable overload handling: if the worker pool or queue is saturated,
 * the client is notified and closed via backpressure callbacks without destabilizing
 * the server acceptor loop.
 */
@Service
public class ClientExecutionManager {

    private static final Logger log = LoggerFactory.getLogger(ClientExecutionManager.class);

    private final ServerMetricsService metricsService;
    private final ClientConnectionManager connectionManager;
    private final ServerEventLogBuffer eventLogBuffer;

    public ClientExecutionManager(ServerMetricsService metricsService,
                                  ClientConnectionManager connectionManager,
                                  ServerEventLogBuffer eventLogBuffer) {
        this.metricsService = Objects.requireNonNull(metricsService, "metricsService must not be null");
        this.connectionManager = Objects.requireNonNull(connectionManager, "connectionManager must not be null");
        this.eventLogBuffer = Objects.requireNonNull(eventLogBuffer, "eventLogBuffer must not be null");
    }

    /**
     * Submits an incoming client connection handler for concurrent execution in the worker pool.
     *
     * @param handler the per-connection handler task
     * @return {@code true} if successfully queued/executed, {@code false} if rejected due to server overload
     */
    public boolean submitClient(ClientHandler handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        ClientConnection connection = Objects.requireNonNull(handler.getConnection(), "connection must not be null");
        String connectionId = connection.getConnectionId();

        // 1. Register connection in active registry and update metrics
        connectionManager.register(connection);

        // 2. Wrap the handler execution with guaranteed lifecycle cleanup
        Runnable wrappedTask = () -> {
            try {
                log.debug("Worker started processing client connection '{}'", connectionId);
                eventLogBuffer.record("TASK_EXECUTED",
                        "Processing client: " + connectionId,
                        "Thread: " + Thread.currentThread().getName());
                handler.run();
            } finally {
                // Ensure connection cleanup when the handler terminates
                connectionManager.unregister(connectionId);
                try {
                    handler.close();
                } catch (Exception e) {
                    log.debug("Error closing handler for '{}': {}", connectionId, e.getMessage());
                }
            }
        };

        // 3. Submit to bounded executor; handle backpressure predictably
        try {
            metricsService.execute(wrappedTask);
            eventLogBuffer.record("TASK_QUEUED",
                    "Queued client task: " + connectionId,
                    "Remote: " + connection.getRemoteAddress());
            return true;
        } catch (ServerOverloadException oe) {
            log.warn("Server overload while dispatching client '{}': {}", connectionId, oe.getMessage());
            eventLogBuffer.record("OVERLOAD_REJECTED",
                    "Rejected client under overload: " + connectionId,
                    oe.getMessage());

            // Backpressure notification to client
            try {
                handler.onOverload(oe);
            } finally {
                connectionManager.unregister(connectionId);
            }
            return false;
        } catch (RuntimeException ex) {
            connectionManager.unregister(connectionId);
            throw ex;
        }
    }

    public ServerMetricsService getMetricsService() {
        return metricsService;
    }

    public ClientConnectionManager getConnectionManager() {
        return connectionManager;
    }
}
