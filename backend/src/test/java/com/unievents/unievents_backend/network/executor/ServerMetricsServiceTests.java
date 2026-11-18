package com.unievents.unievents_backend.network.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ServerMetricsServiceTests {

    @Test
    @DisplayName("ServerMetricsService executes tasks, tracks connections, and returns valid snapshots")
    void serviceDelegationAndMetrics() throws Exception {
        ExecutorProperties props = new ExecutorProperties(2, 4, 10, 60, 5, "srv-test-");
        NamedThreadFactory factory = new NamedThreadFactory(props.getThreadPrefix());
        ServerRejectedExecutionHandler handler = new ServerRejectedExecutionHandler();
        ServerThreadPoolExecutor executor = new ServerThreadPoolExecutor(
                props.getCorePoolSize(), props.getMaxPoolSize(), props.getKeepAliveSeconds(),
                TimeUnit.SECONDS, props.getQueueCapacity(), factory, handler
        );

        ServerMetricsService service = new ServerMetricsService(executor, props);

        try {
            // Test connection tracking
            assertThat(service.trackConnection()).isEqualTo(1);
            assertThat(service.trackConnection()).isEqualTo(2);
            assertThat(service.getActiveConnectionCount()).isEqualTo(2);
            assertThat(service.releaseConnection()).isEqualTo(1);

            // Test execute(Runnable)
            service.execute(() -> {});

            // Test submit(Callable)
            Future<String> future = service.submit(() -> "TASK_DONE");
            assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("TASK_DONE");

            // Test snapshot
            ServerMetricsSnapshot snapshot = service.getMetricsSnapshot();
            assertThat(snapshot.totalSubmittedTasks()).isGreaterThanOrEqualTo(2);
            assertThat(snapshot.activeConnections()).isEqualTo(1);
            assertThat(snapshot.state()).isEqualTo("RUNNING");
        } finally {
            service.shutdown();
        }
    }
}
