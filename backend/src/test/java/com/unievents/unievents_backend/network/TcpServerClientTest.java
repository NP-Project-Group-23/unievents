package com.unievents.unievents_backend.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.network.client.ConnectionListener;
import com.unievents.unievents_backend.network.client.ConnectionState;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.FrameCodec;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import com.unievents.unievents_backend.network.server.RequestDispatcher;
import com.unievents.unievents_backend.network.server.TcpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TcpServer & TcpClient End-to-End Integration Tests")
class TcpServerClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Verify end-to-end PING / PONG round-trip over ephemeral port")
    void testPingPongRoundTrip() throws Exception {
        RequestDispatcher dispatcher = new RequestDispatcher();
        try (TcpServer server = new TcpServer(0, dispatcher);
             TcpClient client = new TcpClient("127.0.0.1", 0, Duration.ofSeconds(5), false)) {

            server.start();
            int boundPort = server.getPort();
            assertTrue(boundPort > 0, "Server must bind to a dynamic ephemeral port");

            client.setHostAndPort("127.0.0.1", boundPort);
            client.connect();
            assertTrue(client.isConnected());

            RequestEnvelope pingReq = RequestEnvelope.of("PING", null);
            ResponseEnvelope response = client.sendSync(pingReq, Duration.ofSeconds(3));

            assertNotNull(response);
            assertEquals(pingReq.requestId(), response.requestId());
            assertTrue(response.isSuccess());
            assertEquals("PONG", response.data().get("message").asText());
        }
    }

    @Test
    @DisplayName("Verify multiple concurrent multiplexed client requests")
    void testMultipleConcurrentClientRequests() throws Exception {
        RequestDispatcher dispatcher = new RequestDispatcher();
        try (TcpServer server = new TcpServer(0, dispatcher);
             TcpClient client = new TcpClient("127.0.0.1", 0, Duration.ofSeconds(10), false)) {

            server.start();
            client.setHostAndPort("127.0.0.1", server.getPort());
            client.connect();

            int requestCount = 40;
            List<CompletableFuture<ResponseEnvelope>> futures = new ArrayList<>();
            List<String> expectedEchoValues = new ArrayList<>();

            for (int i = 0; i < requestCount; i++) {
                String value = "concurrent-payload-" + i;
                expectedEchoValues.add(value);
                ObjectNode payload = MAPPER.createObjectNode().put("index", i).put("payload", value);
                RequestEnvelope req = RequestEnvelope.of("ECHO", payload);

                CompletableFuture<ResponseEnvelope> f = client.sendAsync(req);
                futures.add(f);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(8, TimeUnit.SECONDS);

            for (int i = 0; i < requestCount; i++) {
                ResponseEnvelope resp = futures.get(i).get();
                assertNotNull(resp);
                assertTrue(resp.isSuccess());
                assertEquals(expectedEchoValues.get(i), resp.data().get("payload").asText());
            }
        }
    }

    @Test
    @DisplayName("Verify server idle timeout closes inactive client connection")
    void testServerIdleTimeoutClosingSocket() throws Exception {
        RequestDispatcher dispatcher = new RequestDispatcher();
        // Server with 600ms idle timeout
        try (TcpServer server = new TcpServer(0, dispatcher, null, 600);
             TcpClient client = new TcpClient("127.0.0.1", 0, Duration.ofSeconds(2), false)) {

            server.start();
            client.setHostAndPort("127.0.0.1", server.getPort());

            CountDownLatch disconnectLatch = new CountDownLatch(1);
            client.addConnectionListener((oldState, newState) -> {
                if (newState == ConnectionState.DISCONNECTED) {
                    disconnectLatch.countDown();
                }
            });

            client.connect();
            assertTrue(client.isConnected());

            // Remain idle without sending requests; server idle timeout (600ms) will fire
            boolean disconnected = disconnectLatch.await(3000, TimeUnit.MILLISECONDS);
            assertTrue(disconnected, "Client should transition to DISCONNECTED after server closes idle socket");
            assertFalse(client.isConnected());
        }
    }

    @Test
    @DisplayName("Verify automatic reconnect with exponential backoff after server restart")
    void testReconnectRecovery() throws Exception {
        RequestDispatcher dispatcher = new RequestDispatcher();
        TcpServer server = new TcpServer(0, dispatcher);
        server.start();
        int serverPort = server.getPort();

        CountDownLatch reconnectLatch = new CountDownLatch(1);
        AtomicInteger reconnectCount = new AtomicInteger(0);

        TcpClient client = new TcpClient(
                "127.0.0.1",
                serverPort,
                Duration.ofSeconds(3),
                true,
                5,
                Duration.ofMillis(150),
                Duration.ofMillis(400)
        );

        client.addConnectionListener((oldState, newState) -> {
            if (newState == ConnectionState.CONNECTED && oldState == ConnectionState.RECONNECTING) {
                reconnectCount.incrementAndGet();
                reconnectLatch.countDown();
            }
        });

        try {
            client.connect();
            assertTrue(client.isConnected());

            // Initial ping works
            ResponseEnvelope r1 = client.sendSync(RequestEnvelope.of("PING", null));
            assertTrue(r1.isSuccess());

            // Abruptly kill the server
            server.stop();

            // Wait a brief moment for client to detect disconnect and enter RECONNECTING
            Thread.sleep(300);
            assertEquals(ConnectionState.RECONNECTING, client.getState());

            // Start a new server on the same port
            TcpServer restartedServer = new TcpServer(serverPort, dispatcher);
            try {
                restartedServer.start();

                // Client should auto-reconnect via exponential backoff
                boolean reconnected = reconnectLatch.await(4000, TimeUnit.MILLISECONDS);
                assertTrue(reconnected, "Client must auto-reconnect to restarted server");
                assertTrue(client.isConnected());

                // Post-reconnect request succeeds
                ResponseEnvelope r2 = client.sendSync(RequestEnvelope.of("PING", null));
                assertTrue(r2.isSuccess());
                assertEquals("PONG", r2.data().get("message").asText());
            } finally {
                restartedServer.stop();
            }
        } finally {
            client.close();
        }
    }

    @Test
    @DisplayName("Verify client request timeout triggers TimeoutException")
    void testRequestTimeout() throws Exception {
        var sessions = new com.unievents.unievents_backend.domain.service.SessionManager();
        String token = sessions.createSession(new com.unievents.unievents_backend.domain.model.User(
            1L, "Student", "student@example.com", "hash",
            com.unievents.unievents_backend.domain.model.Role.STUDENT, java.time.Instant.now())).token();
        RequestDispatcher dispatcher = new RequestDispatcher(sessions);
        // Register an intentionally delayed handler
        dispatcher.registerHandler("SLOW_OP", (req, ctx) -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {}
            return ResponseEnvelope.success(req.requestId(), null);
        });

        try (TcpServer server = new TcpServer(0, dispatcher);
             TcpClient client = new TcpClient("127.0.0.1", 0, Duration.ofSeconds(5), false)) {

            server.start();
            client.setHostAndPort("127.0.0.1", server.getPort());
            client.connect();

            RequestEnvelope req = RequestEnvelope.of("SLOW_OP", token, null);
            CompletableFuture<ResponseEnvelope> future = client.sendAsync(req, Duration.ofMillis(250));

            ExecutionException ex = assertThrows(ExecutionException.class, () -> future.get(1000, TimeUnit.MILLISECONDS));
            assertTrue(ex.getCause() instanceof TimeoutException);
        }
    }

    @Test
    @DisplayName("Verify server returns MALFORMED_JSON when client sends non-JSON bytes")
    void testMalformedJsonHandling() throws Exception {
        RequestDispatcher dispatcher = new RequestDispatcher();
        try (TcpServer server = new TcpServer(0, dispatcher)) {
            server.start();

            try (Socket rawSocket = new Socket("127.0.0.1", server.getPort());
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(rawSocket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(rawSocket.getInputStream(), StandardCharsets.UTF_8))) {

                // Send non-JSON garbage line
                writer.write("NOT_A_VALID_JSON_STRING\n");
                writer.flush();

                String responseFrame = FrameCodec.readFrame(reader);
                assertNotNull(responseFrame);

                ResponseEnvelope response = MAPPER.readValue(responseFrame, ResponseEnvelope.class);
                assertTrue(response.isError());
                assertEquals(ErrorCode.MALFORMED_JSON.name(), response.errorCode());
            }
        }
    }
}
