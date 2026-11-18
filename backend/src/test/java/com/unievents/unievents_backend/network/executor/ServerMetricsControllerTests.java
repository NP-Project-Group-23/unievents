package com.unievents.unievents_backend.network.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServerMetricsControllerTests {

    @Test
    @DisplayName("ServerMetricsController returns current metrics snapshot with 200 OK")
    void returnsMetricsSnapshot() {
        ServerMetricsService metricsService = mock(ServerMetricsService.class);
        ServerMetricsSnapshot expectedSnapshot = ServerMetricsSnapshot.create(
                4, 10, 10, 20, 0, 50,
                100, 95, 2, 8, 7, 12.5, 45, "RUNNING"
        );

        when(metricsService.getMetricsSnapshot()).thenReturn(expectedSnapshot);

        ServerMetricsController controller = new ServerMetricsController(metricsService);
        ResponseEntity<ServerMetricsSnapshot> response = controller.getMetrics();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().activeWorkers()).isEqualTo(4);
        assertThat(response.getBody().corePoolSize()).isEqualTo(10);
        assertThat(response.getBody().maxPoolSize()).isEqualTo(20);
        assertThat(response.getBody().totalSubmittedTasks()).isEqualTo(100);
        assertThat(response.getBody().totalCompletedTasks()).isEqualTo(95);
        assertThat(response.getBody().totalRejectedTasks()).isEqualTo(2);
        assertThat(response.getBody().activeConnections()).isEqualTo(8);
        assertThat(response.getBody().state()).isEqualTo("RUNNING");
    }
}
