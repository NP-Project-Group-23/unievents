package com.unievents.unievents_backend.network.executor;

import com.unievents.unievents_backend.network.session.ClientConnection;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service managing client connection lifecycle, active connection registries,
 * and propagating connection count metrics to the monitoring layer.
 */
@Service
public class ClientConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(ClientConnectionManager.class);

    private final Map<String, ClientConnection> activeConnections = new ConcurrentHashMap<>();
    private final ServerMetricsService metricsService;
    private final ServerEventLogBuffer eventLogBuffer;

    public ClientConnectionManager(ServerMetricsService metricsService, ServerEventLogBuffer eventLogBuffer) {
        this.metricsService = Objects.requireNonNull(metricsService, "metricsService must not be null");
        this.eventLogBuffer = Objects.requireNonNull(eventLogBuffer, "eventLogBuffer must not be null");
    }

    /**
     * Registers a new client connection, increments connection metrics, and records an operational event.
     */
    public void register(ClientConnection connection) {
        Objects.requireNonNull(connection, "connection must not be null");
        activeConnections.put(connection.getConnectionId(), connection);
        long count = metricsService.trackConnection();

        eventLogBuffer.record("CONNECTION_OPENED",
                "Client connected: " + connection.getConnectionId(),
                "Remote: " + connection.getRemoteAddress() + ", Active connections: " + count);

        log.info("Registered client connection '{}' from '{}'. Total active: {}",
                connection.getConnectionId(), connection.getRemoteAddress(), count);
    }

    /**
     * Unregisters and closes a client connection, decrements connection metrics, and records an operational event.
     */
    public void unregister(String connectionId) {
        if (connectionId == null) {
            return;
        }
        ClientConnection connection = activeConnections.remove(connectionId);
        if (connection != null) {
            long remaining = metricsService.releaseConnection();
            try {
                connection.close();
            } catch (IOException e) {
                log.debug("Error closing connection '{}': {}", connectionId, e.getMessage());
            }

            eventLogBuffer.record("CONNECTION_CLOSED",
                    "Client disconnected: " + connectionId,
                    "Remote: " + connection.getRemoteAddress() + ", Remaining connections: " + remaining);

            log.info("Unregistered client connection '{}'. Remaining active: {}", connectionId, remaining);
        }
    }

    /**
     * Retrieves an active connection by its ID.
     */
    public Optional<ClientConnection> getConnection(String connectionId) {
        return Optional.ofNullable(activeConnections.get(connectionId));
    }

    /**
     * Returns an unmodifiable snapshot of active connections.
     */
    public Collection<ClientConnection> getActiveConnections() {
        return Collections.unmodifiableCollection(activeConnections.values());
    }

    /**
     * Returns current active connection count.
     */
    public int getActiveCount() {
        return activeConnections.size();
    }

    /**
     * Closes all active client connections during shutdown.
     */
    @PreDestroy
    public void closeAll() {
        log.info("Closing all active client connections (count: {})...", activeConnections.size());
        for (String connectionId : List.copyOf(activeConnections.keySet())) {
            unregister(connectionId);
        }
    }
}
