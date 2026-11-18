package com.unievents.unievents_backend.network.executor;

import com.unievents.unievents_backend.network.session.ClientConnection;
import com.unievents.unievents_backend.network.session.ClientHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ClientExecutionManagerTests {

    @Test
    @DisplayName("ClientExecutionManager successfully registers, executes, and cleans up client handlers")
    void successfulClientExecutionAndCleanup() throws Exception {
        ExecutorProperties props = new ExecutorProperties(2, 4, 10, 60, 5, "exec-mgr-");
        NamedThreadFactory factory = new NamedThreadFactory(props.getThreadPrefix());
        ServerRejectedExecutionHandler handler = new ServerRejectedExecutionHandler();
        ServerThreadPoolExecutor executor = new ServerThreadPoolExecutor(
                props.getCorePoolSize(), props.getMaxPoolSize(), props.getKeepAliveSeconds(),
                TimeUnit.SECONDS, props.getQueueCapacity(), factory, handler
        );
        ServerMetricsService metricsService = new ServerMetricsService(executor, props);
        ServerEventLogBuffer eventBuffer = new ServerEventLogBuffer();
        ClientConnectionManager connManager = new ClientConnectionManager(metricsService, eventBuffer);
        ClientExecutionManager executionManager = new ClientExecutionManager(metricsService, connManager, eventBuffer);

        try {
            CountDownLatch taskExecuted = new CountDownLatch(1);
            AtomicBoolean taskClosed = new AtomicBoolean(false);

            TestClientConnection connection = new TestClientConnection("client-1");
            TestClientHandler clientHandler = new TestClientHandler(connection, () -> {
                taskExecuted.countDown();
            }, () -> taskClosed.set(true));

            boolean accepted = executionManager.submitClient(clientHandler);
            assertThat(accepted).isTrue();

            assertThat(taskExecuted.await(5, TimeUnit.SECONDS)).isTrue();

            // Give worker a moment to complete finally block
            Thread.sleep(50);

            assertThat(taskClosed.get()).isTrue();
            assertThat(connManager.getActiveCount()).isZero();
            assertThat(connManager.getConnection("client-1")).isEmpty();
        } finally {
            metricsService.shutdown();
        }
    }

    @Test
    @DisplayName("ClientExecutionManager handles overload predictably without throwing out to acceptor loop")
    void overloadHandledPredictably() throws Exception {
        // Pool size = 1, Queue = 1 -> Total capacity = 2
        ExecutorProperties props = new ExecutorProperties(1, 1, 1, 60, 5, "overload-mgr-");
        NamedThreadFactory factory = new NamedThreadFactory(props.getThreadPrefix());
        ServerRejectedExecutionHandler handler = new ServerRejectedExecutionHandler();
        ServerThreadPoolExecutor executor = new ServerThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS, 1, factory, handler
        );
        ServerMetricsService metricsService = new ServerMetricsService(executor, props);
        ServerEventLogBuffer eventBuffer = new ServerEventLogBuffer();
        ClientConnectionManager connManager = new ClientConnectionManager(metricsService, eventBuffer);
        ClientExecutionManager executionManager = new ClientExecutionManager(metricsService, connManager, eventBuffer);

        CountDownLatch blockWorkers = new CountDownLatch(1);
        CountDownLatch firstTaskStarted = new CountDownLatch(1);

        try {
            // Task 1: Occupies the single worker thread
            TestClientConnection conn1 = new TestClientConnection("c-1");
            TestClientHandler handler1 = new TestClientHandler(conn1, () -> {
                firstTaskStarted.countDown();
                try {
                    blockWorkers.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
            }, () -> {});
            assertThat(executionManager.submitClient(handler1)).isTrue();
            assertThat(firstTaskStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Task 2: Fills the single queue slot
            TestClientConnection conn2 = new TestClientConnection("c-2");
            TestClientHandler handler2 = new TestClientHandler(conn2, () -> {
                try {
                    blockWorkers.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
            }, () -> {});
            assertThat(executionManager.submitClient(handler2)).isTrue();

            // Task 3: Saturated! Must trigger onOverload and return false without throwing
            AtomicBoolean overloadTriggered = new AtomicBoolean(false);
            TestClientConnection conn3 = new TestClientConnection("c-3");
            TestClientHandler handler3 = new TestClientHandler(conn3, () -> {}, () -> {}) {
                @Override
                public void onOverload(ServerOverloadException ex) {
                    overloadTriggered.set(true);
                }
            };

            boolean accepted = executionManager.submitClient(handler3);
            assertThat(accepted).isFalse();
            assertThat(overloadTriggered.get()).isTrue();

            // Client 3 connection should be cleaned up immediately, not leaked
            assertThat(connManager.getConnection("c-3")).isEmpty();

            // Overload event recorded
            assertThat(eventBuffer.getRecentEvents(10))
                    .anyMatch(event -> "OVERLOAD_REJECTED".equals(event.eventType()));
        } finally {
            blockWorkers.countDown();
            metricsService.shutdown();
        }
    }

    private static class TestClientConnection extends ClientConnection {
        TestClientConnection(String id) {
            super(id, "127.0.0.1:54321", (Closeable) () -> {});
        }
    }

    private static class TestClientHandler implements ClientHandler {
        private final ClientConnection connection;
        private final Runnable task;
        private final Runnable onClose;

        TestClientHandler(ClientConnection connection, Runnable task, Runnable onClose) {
            this.connection = connection;
            this.task = task;
            this.onClose = onClose;
        }

        @Override
        public ClientConnection getConnection() {
            return connection;
        }

        @Override
        public void run() {
            task.run();
        }

        @Override
        public void close() {
            onClose.run();
        }
    }
}
