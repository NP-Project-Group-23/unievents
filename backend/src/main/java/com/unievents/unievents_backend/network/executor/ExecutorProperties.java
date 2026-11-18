package com.unievents.unievents_backend.network.executor;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the UniEvents bounded thread pool executor.
 * Mapped to the prefix {@code unievents.executor}.
 */
@ConfigurationProperties(prefix = "unievents.executor")
public class ExecutorProperties {

    /**
     * Core number of worker threads kept alive in the pool.
     */
    private int corePoolSize = 10;

    /**
     * Maximum number of worker threads allowed in the pool.
     */
    private int maxPoolSize = 20;

    /**
     * Capacity of the bounded task queue (backpressure buffer).
     */
    private int queueCapacity = 50;

    /**
     * Keep-alive time (in seconds) for idle worker threads exceeding corePoolSize.
     */
    private long keepAliveSeconds = 60;

    /**
     * Timeout (in seconds) to wait for running tasks to complete during graceful shutdown.
     */
    private long shutdownTimeoutSeconds = 10;

    /**
     * Name prefix for threads created by the executor thread factory.
     */
    private String threadPrefix = "unievents-worker-";

    public ExecutorProperties() {
    }

    public ExecutorProperties(int corePoolSize, int maxPoolSize, int queueCapacity,
                              long keepAliveSeconds, long shutdownTimeoutSeconds, String threadPrefix) {
        setCorePoolSize(corePoolSize);
        setMaxPoolSize(maxPoolSize);
        setQueueCapacity(queueCapacity);
        setKeepAliveSeconds(keepAliveSeconds);
        setShutdownTimeoutSeconds(shutdownTimeoutSeconds);
        setThreadPrefix(threadPrefix);
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 1) {
            throw new IllegalArgumentException("corePoolSize must be at least 1");
        }
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        if (maxPoolSize < 1) {
            throw new IllegalArgumentException("maxPoolSize must be at least 1");
        }
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least 1");
        }
        this.queueCapacity = queueCapacity;
    }

    public long getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(long keepAliveSeconds) {
        if (keepAliveSeconds < 0) {
            throw new IllegalArgumentException("keepAliveSeconds cannot be negative");
        }
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public long getShutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    public void setShutdownTimeoutSeconds(long shutdownTimeoutSeconds) {
        if (shutdownTimeoutSeconds < 0) {
            throw new IllegalArgumentException("shutdownTimeoutSeconds cannot be negative");
        }
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    public String getThreadPrefix() {
        return threadPrefix;
    }

    public void setThreadPrefix(String threadPrefix) {
        if (threadPrefix == null || threadPrefix.isBlank()) {
            throw new IllegalArgumentException("threadPrefix cannot be blank");
        }
        this.threadPrefix = threadPrefix;
    }

    public void validate() {
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException(
                    "maxPoolSize (" + maxPoolSize + ") cannot be less than corePoolSize (" + corePoolSize + ")");
        }
    }

    @Override
    public String toString() {
        return "ExecutorProperties{" +
                "corePoolSize=" + corePoolSize +
                ", maxPoolSize=" + maxPoolSize +
                ", queueCapacity=" + queueCapacity +
                ", keepAliveSeconds=" + keepAliveSeconds +
                ", shutdownTimeoutSeconds=" + shutdownTimeoutSeconds +
                ", threadPrefix='" + threadPrefix + '\'' +
                '}';
    }
}
