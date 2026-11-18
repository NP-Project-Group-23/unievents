package com.unievents.unievents_backend.network.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerThreadPoolExecutorTests {

    @Test
    @DisplayName("Worker threads are given predictable names, normal priority, and are non-daemon")
    void workerThreadNamingAndProperties() throws Exception {
        NamedThreadFactory factory = new NamedThreadFactory("test-worker-");
        ServerRejectedExecutionHandler handler = new ServerRejectedExecutionHandler();
        ServerThreadPoolExecutor executor = new ServerThreadPoolExecutor(
                2, 2, 60, TimeUnit.SECONDS, 10, factory, handler
        );

        try {
            CompletableFuture<ThreadInfo> future = new CompletableFuture<>();
            executor.execute(() -> {
                Thread current = Thread.currentThread();
                future.complete(new ThreadInfo(current.getName(), current.isDaemon(), current.getPriority()));
            });

            ThreadInfo info = future.get(5, TimeUnit.SECONDS);
            assertThat(info.name()).startsWith("test-worker-");
            assertThat(info.isDaemon()).isFalse();
            assertThat(info.priority()).isEqualTo(Thread.NORM_PRIORITY);
            assertThat(factory.getCreatedCount()).isGreaterThanOrEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Lifecycle hooks track submitted tasks, completed tasks, and execution duration")
    void taskExecutionAndLifecycleTracking() throws Exception {
        NamedThreadFactory factory = new NamedThreadFactory("test-lifecycle-");
        ServerRejectedExecutionHandler handler = new ServerRejectedExecutionHandler();
        ServerThreadPoolExecutor executor = new ServerThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS, 10, factory, handler
        );

        try {
            CountDownLatch latch = new CountDownLatch(5);
            for (int i = 0; i < 5; i++) {
                executor.execute(() -> {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            ServerMetricsSnapshot snapshot = executor.getMetricsSnapshot();
            assertThat(snapshot.totalSubmittedTasks()).isEqualTo(5);
            assertThat(snapshot.totalCompletedTasks()).isEqualTo(5);
            assertThat(snapshot.totalRejectedTasks()).isZero();
            assertThat(snapshot.averageExecutionTimeMs()).isGreaterThan(0.0);
            assertThat(snapshot.maxExecutionTimeMs()).isGreaterThanOrEqualTo(0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Bounded capacity enforces backpressure and triggers ServerOverloadException on saturation")
    void boundedQueueAndOverloadRejection() throws Exception {
        NamedThreadFactory factory = new NamedThreadFactory("test-overload-");
        ServerRejectedExecutionHandler handler = new ServerRejectedExecutionHandler();

        // Total capacity: maxPoolSize(2) + queueCapacity(2) = 4 concurrent tasks
        ServerThreadPoolExecutor executor = new ServerThreadPoolExecutor(
                2, 2, 60, TimeUnit.SECONDS, 2, factory, handler
        );

        CountDownLatch blockWorkers = new CountDownLatch(1);
        CountDownLatch tasksStarted = new CountDownLatch(2);

        try {
            // Task 1 & 2: Occupy the 2 active worker threads
            for (int i = 0; i < 2; i++) {
                executor.execute(() -> {
                    tasksStarted.countDown();
                    try {
                        blockWorkers.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            // Wait for both worker threads to be actively running
            assertThat(tasksStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Task 3 & 4: Fill the bounded queue (capacity = 2)
            for (int i = 0; i < 2; i++) {
                executor.execute(() -> {
                    try {
                        blockWorkers.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            // Check queue is now full
            assertThat(executor.getQueue().size()).isEqualTo(2);
            assertThat(executor.getQueue().remainingCapacity()).isZero();

            // Task 5: Must be rejected because active workers = 2 and queue = 2
            assertThatThrownBy(() -> executor.execute(() -> {}))
                    .isInstanceOf(ServerOverloadException.class)
                    .satisfies(ex -> {
                        ServerOverloadException soe = (ServerOverloadException) ex;
                        assertThat(soe.getErrorCode()).isEqualTo("SERVER_OVERLOAD");
                        assertThat(soe.getActiveWorkers()).isEqualTo(2);
                        assertThat(soe.getQueuedTasks()).isEqualTo(2);
                        assertThat(soe.getMaxPoolSize()).isEqualTo(2);
                        assertThat(soe.getQueueCapacity()).isEqualTo(2);
                    });

            assertThat(handler.getRejectedCount()).isEqualTo(1);

            ServerMetricsSnapshot snapshot = executor.getMetricsSnapshot();
            assertThat(snapshot.totalRejectedTasks()).isEqualTo(1);
            assertThat(snapshot.queuedTasks()).isEqualTo(2);
            assertThat(snapshot.queueRemainingCapacity()).isZero();
        } finally {
            blockWorkers.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Metrics snapshot accurately represents live state, active counts, and peak workers")
    void metricsSnapshotAccuracy() throws Exception {
        NamedThreadFactory factory = new NamedThreadFactory("test-metrics-");
        ServerRejectedExecutionHandler handler = new ServerRejectedExecutionHandler();
        ServerThreadPoolExecutor executor = new ServerThreadPoolExecutor(
                3, 5, 60, TimeUnit.SECONDS, 10, factory, handler
        );

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(3);

        try {
            for (int i = 0; i < 3; i++) {
                executor.execute(() -> {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            ServerMetricsSnapshot snapshot = executor.getMetricsSnapshot();
            assertThat(snapshot.corePoolSize()).isEqualTo(3);
            assertThat(snapshot.maxPoolSize()).isEqualTo(5);
            assertThat(snapshot.activeWorkers()).isEqualTo(3);
            assertThat(snapshot.peakActiveWorkers()).isGreaterThanOrEqualTo(3);
            assertThat(snapshot.state()).isEqualTo("RUNNING");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Graceful shutdown allows running tasks to complete and cleans up pool resources")
    void gracefulShutdown() throws Exception {
        NamedThreadFactory factory = new NamedThreadFactory("test-shutdown-");
        ServerRejectedExecutionHandler handler = new ServerRejectedExecutionHandler();
        ServerThreadPoolExecutor executor = new ServerThreadPoolExecutor(
                2, 2, 60, TimeUnit.SECONDS, 5, factory, handler
        );

        AtomicBoolean taskCompleted = new AtomicBoolean(false);
        CountDownLatch taskStarted = new CountDownLatch(1);

        executor.execute(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(100);
                taskCompleted.set(true);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(taskStarted.await(5, TimeUnit.SECONDS)).isTrue();

        boolean terminated = executor.gracefulShutdown(5, TimeUnit.SECONDS);
        assertThat(terminated).isTrue();
        assertThat(taskCompleted.get()).isTrue();
        assertThat(executor.isShutdown()).isTrue();
        assertThat(executor.isTerminated()).isTrue();

        ServerMetricsSnapshot finalSnapshot = executor.getMetricsSnapshot();
        assertThat(finalSnapshot.state()).isEqualTo("TERMINATED");
        assertThat(finalSnapshot.totalCompletedTasks()).isEqualTo(1);
    }

    @Test
    @DisplayName("Uncaught exceptions in tasks are handled without killing the worker pool")
    void uncaughtTaskExceptionHandling() throws Exception {
        NamedThreadFactory factory = new NamedThreadFactory("test-exception-");
        ServerRejectedExecutionHandler handler = new ServerRejectedExecutionHandler();
        ServerThreadPoolExecutor executor = new ServerThreadPoolExecutor(
                1, 2, 60, TimeUnit.SECONDS, 5, factory, handler
        );

        try {
            // Task that throws an unchecked exception
            executor.execute(() -> {
                throw new RuntimeException("Simulated unexpected failure");
            });

            // Subsequent task should execute normally
            CompletableFuture<String> recovery = new CompletableFuture<>();
            executor.execute(() -> recovery.complete("SUCCESS"));

            String result = recovery.get(5, TimeUnit.SECONDS);
            assertThat(result).isEqualTo("SUCCESS");
            assertThat(executor.getMetricsSnapshot().totalSubmittedTasks()).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Active connection counter tracks clients and prevents negative counts")
    void connectionTracking() {
        NamedThreadFactory factory = new NamedThreadFactory("test-conn-");
        ServerRejectedExecutionHandler handler = new ServerRejectedExecutionHandler();
        ServerThreadPoolExecutor executor = new ServerThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS, 5, factory, handler
        );

        try {
            assertThat(executor.getActiveConnections()).isZero();
            assertThat(executor.incrementActiveConnections()).isEqualTo(1);
            assertThat(executor.incrementActiveConnections()).isEqualTo(2);
            assertThat(executor.decrementActiveConnections()).isEqualTo(1);
            assertThat(executor.decrementActiveConnections()).isEqualTo(0);
            assertThat(executor.decrementActiveConnections()).isZero(); // Floor at zero
        } finally {
            executor.shutdownNow();
        }
    }

    private record ThreadInfo(String name, boolean isDaemon, int priority) {}
}
