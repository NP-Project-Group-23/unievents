package com.unievents.unievents_backend.network.executor;

import com.unievents.unievents_backend.network.session.ClientConnection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ClientConnectionManagerTests {

    @Test
    @DisplayName("ClientConnectionManager tracks active connections and decrements on unregister")
    void connectionLifecycle() {
        ServerMetricsService metricsService = mock(ServerMetricsService.class);
        ServerEventLogBuffer eventBuffer = new ServerEventLogBuffer();
        ClientConnectionManager manager = new ClientConnectionManager(metricsService, eventBuffer);

        AtomicBoolean closed = new AtomicBoolean(false);
        ClientConnection conn = new ClientConnection("test-conn-1", "192.168.1.10:8080", (Closeable) () -> closed.set(true));

        manager.register(conn);
        assertThat(manager.getActiveCount()).isEqualTo(1);
        assertThat(manager.getConnection("test-conn-1")).contains(conn);
        verify(metricsService).trackConnection();

        manager.unregister("test-conn-1");
        assertThat(manager.getActiveCount()).isZero();
        assertThat(manager.getConnection("test-conn-1")).isEmpty();
        assertThat(closed.get()).isTrue();
        verify(metricsService).releaseConnection();
    }

    @Test
    @DisplayName("closeAll cleans up all active connections during shutdown")
    void closeAllConnections() {
        ServerMetricsService metricsService = mock(ServerMetricsService.class);
        ServerEventLogBuffer eventBuffer = new ServerEventLogBuffer();
        ClientConnectionManager manager = new ClientConnectionManager(metricsService, eventBuffer);

        AtomicBoolean closed1 = new AtomicBoolean(false);
        AtomicBoolean closed2 = new AtomicBoolean(false);

        manager.register(new ClientConnection("c1", "10.0.0.1:1001", (Closeable) () -> closed1.set(true)));
        manager.register(new ClientConnection("c2", "10.0.0.2:1002", (Closeable) () -> closed2.set(true)));

        assertThat(manager.getActiveCount()).isEqualTo(2);

        manager.closeAll();
        assertThat(manager.getActiveCount()).isZero();
        assertThat(closed1.get()).isTrue();
        assertThat(closed2.get()).isTrue();
    }
}
