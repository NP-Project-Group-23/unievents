package com.unievents.unievents_backend.network.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, instrumented {@link ThreadPoolExecutor} that forms the core concurrency
 * foundation for the UniEvents server (Member 5, Task B16).
 * <p>
 * Implements thread lifecycle tracking (beforeExecute, afterExecute, terminated),
 * observable overload backpressure, execution duration tracking, and live server metrics.
 */
public class ServerThreadPoolExecutor extends ThreadPoolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ServerThreadPoolExecutor.class);

    private final NamedThreadFactory namedThreadFactory;
    private final ServerRejectedExecutionHandler rejectionHandler;
    private final int queueCapacity;

    private final AtomicLong totalSubmittedTasks = new AtomicLong(0);
    private final AtomicLong totalCompletedTasks = new AtomicLong(0);
    private final AtomicLong totalExecutionTimeNanos = new AtomicLong(0);
    private final AtomicLong maxExecutionTimeNanos = new AtomicLong(0);
    private final AtomicInteger peakActiveWorkers = new AtomicInteger(0);
    private final AtomicLong activeConnections = new AtomicLong(0);

    private final ThreadLocal<Long> taskStartTime = new ThreadLocal<>();

    public ServerThreadPoolExecutor(int corePoolSize,
                                  int maximumPoolSize,
                                  long keepAliveTime,
                                  TimeUnit unit,
                                  int queueCapacity,
                                  NamedThreadFactory threadFactory,
                                  ServerRejectedExecutionHandler handler) {
        super(corePoolSize,
                maximumPoolSize,
                keepAliveTime,
                unit,
                new ArrayBlockingQueue<>(queueCapacity),
                Objects.requireNonNull(threadFactory, "threadFactory must not be null"),
                Objects.requireNonNull(handler, "rejectionHandler must not be null"));

        this.namedThreadFactory = threadFactory;
        this.rejectionHandler = handler;
        this.queueCapacity = queueCapacity;

        log.info("Initialized ServerThreadPoolExecutor: corePoolSize={}, maxPoolSize={}, queueCapacity={}, keepAlive={}s",
                corePoolSize, maximumPoolSize, queueCapacity, unit.toSeconds(keepAliveTime));
    }

    @Override
    public void execute(Runnable command) {
        totalSubmittedTasks.incrementAndGet();
        super.execute(command);
    }

    @Override
    protected void beforeExecute(Thread t, Runnable r) {
        super.beforeExecute(t, r);
        taskStartTime.set(System.nanoTime());

        int currentActive = getActiveCount();
        peakActiveWorkers.updateAndGet(peak -> Math.max(peak, currentActive));

        log.trace("Worker '{}' beginning task execution. Current active workers: {}", t.getName(), currentActive);
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        try {
            Long startTime = taskStartTime.get();
            if (startTime != null) {
                long durationNanos = System.nanoTime() - startTime;
                taskStartTime.remove();
                totalExecutionTimeNanos.addAndGet(durationNanos);
                maxExecutionTimeNanos.updateAndGet(max -> Math.max(max, durationNanos));
            }

            totalCompletedTasks.incrementAndGet();

            Throwable failure = t;
            if (failure == null && r instanceof Future<?> future && future.isDone()) {
                try {
                    future.get();
                } catch (CancellationException ce) {
                    log.debug("Task was cancelled: {}", ce.getMessage());
                } catch (ExecutionException ee) {
                    failure = ee.getCause();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            if (failure != null) {
                log.error("Task encountered an unhandled exception during execution: {}", failure.getMessage(), failure);
            }
        } finally {
            super.afterExecute(r, t);
        }
    }

    @Override
    protected void terminated() {
        super.terminated();
        log.info("ServerThreadPoolExecutor TERMINATED. Final metrics - Submitted: {}, Completed: {}, Rejected: {}",
                totalSubmittedTasks.get(), totalCompletedTasks.get(), rejectionHandler.getRejectedCount());
    }

    /**
     * Executes a clean, two-phase graceful shutdown:
     * 1. Stops accepting new tasks and waits up to {@code timeout} for in-flight tasks.
     * 2. If timeout elapses, forces shutdown and cancels running tasks.
     *
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout argument
     * @return {@code true} if this executor terminated, {@code false} if timeout elapsed before termination
     */
    public boolean gracefulShutdown(long timeout, TimeUnit unit) {
        log.info("Initiating graceful shutdown of ServerThreadPoolExecutor (timeout: {} {})...", timeout, unit);
        shutdown();
        try {
            if (!awaitTermination(timeout, unit)) {
                log.warn("Timed out waiting for tasks to complete. Forcing shutdownNow()...");
                shutdownNow();
                if (!awaitTermination(timeout, unit)) {
                    log.error("ServerThreadPoolExecutor did not terminate after shutdownNow()");
                }
            } else {
                log.info("ServerThreadPoolExecutor shut down cleanly.");
            }
        } catch (InterruptedException ie) {
            log.warn("Shutdown interrupted, forcing shutdownNow()...");
            shutdownNow();
            Thread.currentThread().interrupt();
        }
        return isTerminated();
    }

    /**
     * Captures a point-in-time snapshot of the executor's authentic metrics.
     */
    public ServerMetricsSnapshot getMetricsSnapshot() {
        long completed = totalCompletedTasks.get();
        long totalNanos = totalExecutionTimeNanos.get();
        double avgDurationMs = completed > 0 ? (totalNanos / 1_000_000.0) / completed : 0.0;
        long maxDurationMs = maxExecutionTimeNanos.get() / 1_000_000;

        String state;
        if (isTerminated()) {
            state = "TERMINATED";
        } else if (isShutdown()) {
            state = "SHUTTING_DOWN";
        } else {
            state = "RUNNING";
        }

        return ServerMetricsSnapshot.create(
                getActiveCount(),
                getPoolSize(),
                getCorePoolSize(),
                getMaximumPoolSize(),
                getQueue().size(),
                getQueue().remainingCapacity(),
                totalSubmittedTasks.get(),
                completed,
                rejectionHandler.getRejectedCount(),
                activeConnections.get(),
                peakActiveWorkers.get(),
                Math.round(avgDurationMs * 100.0) / 100.0,
                maxDurationMs,
                state
        );
    }

    // Connection tracking methods for Task B17 / gateway integration
    public long incrementActiveConnections() {
        return activeConnections.incrementAndGet();
    }

    public long decrementActiveConnections() {
        return activeConnections.updateAndGet(c -> Math.max(0, c - 1));
    }

    public long getActiveConnections() {
        return activeConnections.get();
    }

    public NamedThreadFactory getNamedThreadFactory() {
        return namedThreadFactory;
    }

    public ServerRejectedExecutionHandler getRejectionHandler() {
        return rejectionHandler;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }
}
