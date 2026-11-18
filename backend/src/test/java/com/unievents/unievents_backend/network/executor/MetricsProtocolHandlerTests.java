package com.unievents.unievents_backend.network.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricsProtocolHandlerTests {

    @Test
    @DisplayName("MetricsProtocolHandler handles GET_METRICS and produces valid envelope")
    void handlesGetMetrics() {
        ServerMetricsService metricsService = mock(ServerMetricsService.class);
        ServerMetricsSnapshot snapshot = ServerMetricsSnapshot.create(
                2, 5, 10, 20, 0, 50,
                50, 48, 0, 3, 4, 8.5, 25, "RUNNING"
        );
        when(metricsService.getMetricsSnapshot()).thenReturn(snapshot);

        MetricsProtocolHandler handler = new MetricsProtocolHandler(metricsService);
        MetricsProtocolHandler.MetricsEnvelope envelope = handler.handle("req-test-123");

        assertThat(envelope.requestId()).isEqualTo("req-test-123");
        assertThat(envelope.type()).isEqualTo("GET_METRICS");
        assertThat(envelope.status()).isEqualTo("SUCCESS");
        assertThat(envelope.errorCode()).isNull();
        assertThat(envelope.data()).isNotNull();
        assertThat(envelope.data().activeWorkers()).isEqualTo(2);
        assertThat(envelope.data().maxPoolSize()).isEqualTo(20);
        assertThat(envelope.data().state()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("MetricsProtocolHandler generates fallback requestId if missing or blank")
    void generatesFallbackRequestId() {
        ServerMetricsService metricsService = mock(ServerMetricsService.class);
        when(metricsService.getMetricsSnapshot()).thenReturn(
                ServerMetricsSnapshot.create(0, 0, 10, 20, 0, 50, 0, 0, 0, 0, 0, 0.0, 0, "RUNNING")
        );

        MetricsProtocolHandler handler = new MetricsProtocolHandler(metricsService);
        MetricsProtocolHandler.MetricsEnvelope envelope = handler.handle(null);

        assertThat(envelope.requestId()).isNotNull().startsWith("req-");
    }
}
