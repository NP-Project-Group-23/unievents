package com.unievents.unievents_backend.network;

import com.unievents.unievents_backend.network.executor.*;
import com.unievents.unievents_backend.network.protocol.*;
import com.unievents.unievents_backend.network.server.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TcpLifecycleTests {
    private AnnotationConfigApplicationContext context(int workers) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "unievents.tcp.port", "0", "unievents.tcp.idle-timeout-ms", "2000",
            "unievents.executor.core-pool-size", workers,
            "unievents.executor.max-pool-size", workers,
            "unievents.executor.queue-capacity", "1",
            "unievents.executor.shutdown-timeout-seconds", "1")));
        context.registerBean(RequestDispatcher.class, () -> new RequestDispatcher());
        context.register(ExecutorConfiguration.class, ServerMetricsService.class,
            ClientConnectionManager.class, ClientExecutionManager.class,
            ServerEventLogBuffer.class, TcpServerConfiguration.class);
        context.refresh();
        return context;
    }

    private Socket connect(TcpServer server) throws IOException {
        var socket = new Socket("127.0.0.1", server.getPort());
        socket.setSoTimeout(5000);
        return socket;
    }

    private ResponseEnvelope read(Socket socket) throws IOException {
        return FrameCodec.readEnvelope(new BufferedReader(new InputStreamReader(
            socket.getInputStream(), StandardCharsets.UTF_8)), ResponseEnvelope.class);
    }

    private void ping(Socket socket) throws IOException {
        FrameCodec.writeEnvelope(new BufferedWriter(new OutputStreamWriter(
            socket.getOutputStream(), StandardCharsets.UTF_8)),
            new RequestEnvelope("ping-test", "PING", null, null));
        var response = read(socket);
        assertEquals("ping-test", response.requestId());
        assertEquals("PONG", response.data().path("message").asText());
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Condition not reached before deadline");
    }

    @Test
    void startupServesRequestsWhileAnotherClientIsIdle() throws Exception {
        try (var context = context(2)) {
            var server = context.getBean(TcpServer.class);
            var metrics = context.getBean(ServerMetricsService.class);
            assertTrue(server.isRunning());
            try (var idle = connect(server); var active = connect(server)) {
                ping(active);
                await(() -> metrics.getActiveConnectionCount() == 2);
                assertTrue(metrics.getMetricsSnapshot().activeWorkers() <= 2);
            }
            await(() -> metrics.getActiveConnectionCount() == 0);
            await(() -> metrics.getMetricsSnapshot().totalCompletedTasks() == 2);
        }
    }

    @Test
    void saturationRejectsAndShutdownClosesActiveAndQueuedSockets() throws Exception {
        var context = context(1);
        var server = context.getBean(TcpServer.class);
        int boundPort = server.getPort();
        var metrics = context.getBean(ServerMetricsService.class);
        var executor = context.getBean(ServerThreadPoolExecutor.class);
        try (var active = connect(server)) {
            ping(active);
            try (var queued = connect(server)) {
                await(() -> metrics.getMetricsSnapshot().queuedTasks() == 1);
                try (var rejected = connect(server)) {
                    assertEquals("SERVER_OVERLOAD", read(rejected).errorCode());
                    assertEquals(-1, rejected.getInputStream().read());
                }
                await(() -> metrics.getActiveConnectionCount() == 2);
                assertEquals(1, metrics.getMetricsSnapshot().totalRejectedTasks());
                context.close();
                assertFalse(server.isRunning());
                assertTrue(executor.isTerminated());
                assertEquals(0, metrics.getActiveConnectionCount());
                assertEquals(-1, active.getInputStream().read());
                assertEquals(-1, queued.getInputStream().read());
                assertThrows(IOException.class, () -> new Socket("127.0.0.1", boundPort));
            }
        } finally { context.close(); }
    }

    @Test
    void idleSocketTimesOutAndReleasesWorker() throws Exception {
        try (var context = context(1)) {
            var metrics = context.getBean(ServerMetricsService.class);
            try (var idle = connect(context.getBean(TcpServer.class))) {
                assertEquals(-1, idle.getInputStream().read());
                await(() -> metrics.getActiveConnectionCount() == 0);
            }
        }
    }
}
