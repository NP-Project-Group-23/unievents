package com.unievents.unievents_backend.network.executor;

import java.time.Instant;

/**
 * Immutable snapshot of UniEvents server performance and worker pool metrics.
 * Designed for consumption by the monitoring screen (F07), admin gateway (M5),
 * and TCP GET_METRICS handlers.
 */
public record ServerMetricsSnapshot(
        Instant timestamp,
        int activeWorkers,
        int currentPoolSize,
        int corePoolSize,
        int maxPoolSize,
        int queuedTasks,
        int queueRemainingCapacity,
        long totalSubmittedTasks,
        long totalCompletedTasks,
        long totalRejectedTasks,
        long activeConnections,
        int peakActiveWorkers,
        double averageExecutionTimeMs,
        long maxExecutionTimeMs,
        String state
) {
    public static ServerMetricsSnapshot create(
            int activeWorkers,
            int currentPoolSize,
            int corePoolSize,
            int maxPoolSize,
            int queuedTasks,
            int queueRemainingCapacity,
            long totalSubmittedTasks,
            long totalCompletedTasks,
            long totalRejectedTasks,
            long activeConnections,
            int peakActiveWorkers,
            double averageExecutionTimeMs,
            long maxExecutionTimeMs,
            String state
    ) {
        return new ServerMetricsSnapshot(
                Instant.now(),
                activeWorkers,
                currentPoolSize,
                corePoolSize,
                maxPoolSize,
                queuedTasks,
                queueRemainingCapacity,
                totalSubmittedTasks,
                totalCompletedTasks,
                totalRejectedTasks,
                activeConnections,
                peakActiveWorkers,
                averageExecutionTimeMs,
                maxExecutionTimeMs,
                state
        );
    }
}
