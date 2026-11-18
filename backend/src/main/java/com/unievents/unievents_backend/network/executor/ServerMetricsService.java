package com.unievents.unievents_backend.network.executor;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Service managing the server's thread pool executor and exposing genuine server metrics.
 * Coordinates graceful shutdown on application exit and tracks active client connections.
 */
@Service
public class ServerMetricsService {

    private static final Logger log = LoggerFactory.getLogger(ServerMetricsService.class);

    private final ServerThreadPoolExecutor executor;
    private final ExecutorProperties properties;

    public ServerMetricsService(ServerThreadPoolExecutor executor, ExecutorProperties properties) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Submits a runnable task to the bounded worker pool.
     *
     * @param task the runnable to execute
     * @throws ServerOverloadException if the pool and queue are saturated
     */
    public void execute(Runnable task) {
        executor.execute(task);
    }

    /**
     * Submits a value-returning task for execution in the worker pool.
     *
     * @param task the callable to execute
     * @return a Future representing pending completion
     * @throws ServerOverloadException if the pool and queue are saturated
     */
    public <T> Future<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    /**
     * Returns an authentic point-in-time metrics snapshot of the server and worker pool.
     */
    public ServerMetricsSnapshot getMetricsSnapshot() {
        return executor.getMetricsSnapshot();
    }

    /**
     * Records a new client connection (TCP/WebSocket).
     */
    public long trackConnection() {
        long count = executor.incrementActiveConnections();
        log.debug("Client connected. Total active connections: {}", count);
        return count;
    }

    /**
     * Records closure of a client connection.
     */
    public long releaseConnection() {
        long count = executor.decrementActiveConnections();
        log.debug("Client disconnected. Remaining active connections: {}", count);
        return count;
    }

    /**
     * Returns current active client connection count.
     */
    public long getActiveConnectionCount() {
        return executor.getActiveConnections();
    }

    /**
     * Exposes the underlying {@link ServerThreadPoolExecutor} for direct integration
     * with the TCP server acceptor loop.
     */
    public ServerThreadPoolExecutor getExecutor() {
        return executor;
    }

    @PreDestroy
    public void shutdown() {
        log.info("ServerMetricsService shutting down worker pool...");
        executor.gracefulShutdown(properties.getShutdownTimeoutSeconds(), TimeUnit.SECONDS);
    }
}
