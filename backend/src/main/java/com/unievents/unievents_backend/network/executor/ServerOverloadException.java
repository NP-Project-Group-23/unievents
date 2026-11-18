package com.unievents.unievents_backend.network.executor;

import java.util.concurrent.RejectedExecutionException;

/**
 * Thrown when the UniEvents bounded executor reaches capacity and cannot
 * accept additional tasks (backpressure activation).
 */
public class ServerOverloadException extends RejectedExecutionException {

    public static final String ERROR_CODE = "SERVER_OVERLOAD";

    private final int activeWorkers;
    private final int queuedTasks;
    private final int maxPoolSize;
    private final int queueCapacity;

    public ServerOverloadException(int activeWorkers, int queuedTasks, int maxPoolSize, int queueCapacity) {
        super(String.format("Server is overloaded. Active workers: %d/%d, Queued tasks: %d/%d. Task was rejected.",
                activeWorkers, maxPoolSize, queuedTasks, queueCapacity));
        this.activeWorkers = activeWorkers;
        this.queuedTasks = queuedTasks;
        this.maxPoolSize = maxPoolSize;
        this.queueCapacity = queueCapacity;
    }

    public ServerOverloadException(String message, int activeWorkers, int queuedTasks, int maxPoolSize, int queueCapacity) {
        super(message);
        this.activeWorkers = activeWorkers;
        this.queuedTasks = queuedTasks;
        this.maxPoolSize = maxPoolSize;
        this.queueCapacity = queueCapacity;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }

    public int getActiveWorkers() {
        return activeWorkers;
    }

    public int getQueuedTasks() {
        return queuedTasks;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }
}
