package com.unievents.unievents_backend.network.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom {@link ThreadFactory} for the UniEvents server worker pool.
 * Generates predictably named, non-daemon worker threads with dedicated
 * uncaught exception handling.
 */
public class NamedThreadFactory implements ThreadFactory {

    private static final Logger log = LoggerFactory.getLogger(NamedThreadFactory.class);

    private final String prefix;
    private final AtomicInteger threadSequence = new AtomicInteger(1);
    private final ThreadGroup group;

    public NamedThreadFactory(String prefix) {
        this.prefix = Objects.requireNonNull(prefix, "prefix must not be null");
        this.group = Thread.currentThread().getThreadGroup();
    }

    @Override
    public Thread newThread(Runnable r) {
        String threadName = prefix + threadSequence.getAndIncrement();
        Thread thread = new Thread(group, r, threadName, 0);

        if (thread.isDaemon()) {
            thread.setDaemon(false);
        }
        if (thread.getPriority() != Thread.NORM_PRIORITY) {
            thread.setPriority(Thread.NORM_PRIORITY);
        }

        thread.setUncaughtExceptionHandler((t, e) ->
                log.error("Uncaught exception in worker thread '{}': {}", t.getName(), e.getMessage(), e));

        log.debug("Created new worker thread '{}'", threadName);
        return thread;
    }

    public int getCreatedCount() {
        return threadSequence.get() - 1;
    }

    public String getPrefix() {
        return prefix;
    }
}
