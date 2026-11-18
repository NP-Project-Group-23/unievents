package com.unievents.unievents_backend.network.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom {@link RejectedExecutionHandler} for the UniEvents server.
 * Tracks rejection metrics and throws {@link ServerOverloadException} to apply
 * observable backpressure to incoming requests when workers and queue are saturated.
 */
public class ServerRejectedExecutionHandler implements RejectedExecutionHandler {

    private static final Logger log = LoggerFactory.getLogger(ServerRejectedExecutionHandler.class);

    private final AtomicLong rejectedCount = new AtomicLong(0);

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        long currentRejections = rejectedCount.incrementAndGet();

        int activeCount = executor.getActiveCount();
        int queueSize = executor.getQueue().size();
        int maxPoolSize = executor.getMaximumPoolSize();
        int queueCapacity = queueSize + executor.getQueue().remainingCapacity();

        log.warn("BACKPRESSURE ACTIVATED: Task rejected under overload (#{} total). Active: {}/{}, Queued: {}/{}",
                currentRejections, activeCount, maxPoolSize, queueSize, queueCapacity);

        throw new ServerOverloadException(activeCount, queueSize, maxPoolSize, queueCapacity);
    }

    public long getRejectedCount() {
        return rejectedCount.get();
    }
}
