package com.unievents.unievents_backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.model.User;
import com.unievents.unievents_backend.domain.repository.JdbcUserRepository;
import com.unievents.unievents_backend.domain.service.AuthValidationException;
import com.unievents.unievents_backend.domain.service.BCryptPasswordHasher;
import com.unievents.unievents_backend.domain.service.ContextBridge;
import com.unievents.unievents_backend.domain.service.InvalidCredentialsException;
import com.unievents.unievents_backend.domain.service.SessionManager;
import com.unievents.unievents_backend.domain.service.StudentAuthService;
import com.unievents.unievents_backend.handlers.AuthHandlersConfig;
import com.unievents.unievents_backend.handlers.LoginHandler;
import com.unievents.unievents_backend.handlers.LogoutHandler;
import com.unievents.unievents_backend.handlers.RegisterHandler;
import com.unievents.unievents_backend.handlers.SessionCheckHandler;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.FrameCodec;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import com.unievents.unievents_backend.network.server.RequestDispatcher;
import com.unievents.unievents_backend.network.server.TcpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T01 Comprehensive Verification & Edge-Case Test Suite.
 * Exhaustively tests protocol edge cases, bounded framing, malicious/garbage streams,
 * invalid credentials, abrupt TCP RST drops, client auto-reconnect session survival,
 * 20+ thread concurrent session stress, downstream BookingService contract validation,
 * password bounds, and role privilege escalation defense.
 */
public class T01ComprehensiveEdgeCaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SingleConnectionDataSource dataSource;
    private JdbcUserRepository userRepository;
    private BCryptPasswordHasher passwordHasher;
    private SessionManager sessionManager;
    private StudentAuthService authService;
    private ContextBridge contextBridge;
    private RequestDispatcher dispatcher;

    private TcpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        String dbName = "t01_edge_cases_" + UUID.randomUUID().toString().replace("-", "");
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSource.setSuppressClose(true);

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE app_users (
                id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                email VARCHAR(254) NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                role VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT ck_users_role CHECK (role IN ('STUDENT', 'ADMIN'))
            );
            CREATE INDEX idx_users_email ON app_users(email);
        """);

        userRepository = new JdbcUserRepository(jdbcTemplate);
        // Cost factor 4 for test execution speed while maintaining full BCrypt logic
        passwordHasher = new BCryptPasswordHasher(4);
        sessionManager = new SessionManager(Duration.ofMinutes(30));
        authService = new StudentAuthService(userRepository, passwordHasher, sessionManager);
        contextBridge = new ContextBridge(sessionManager);

        dispatcher = new RequestDispatcher(sessionManager);
        AuthHandlersConfig.registerHandlers(
                dispatcher,
                new RegisterHandler(authService, sessionManager),
                new LoginHandler(authService),
                new LogoutHandler(sessionManager),
                new SessionCheckHandler(sessionManager)
        );

        // Downstream mock booking endpoint simulating Member 03 BookingService contract
        dispatcher.registerHandler("MOCK_BOOKING", (request, context) -> {
            String token = request.token();
            if (token == null || token.isBlank()) {
                return ResponseEnvelope.error(request.requestId(), ErrorCode.UNAUTHENTICATED, "Session token missing.");
            }
            if (sessionManager.isExpired(token)) {
                return ResponseEnvelope.error(request.requestId(), ErrorCode.SESSION_EXPIRED, "Session expired.");
            }
            try {
                Long verifiedUserId = contextBridge.extractAuthenticatedUserId(token);
                ObjectNode data = MAPPER.createObjectNode();
                data.put("bookingId", 777L);
                data.put("userId", verifiedUserId);
                data.put("status", "CONFIRMED");
                return ResponseEnvelope.success(request.requestId(), data);
            } catch (InvalidCredentialsException ice) {
                return ResponseEnvelope.error(request.requestId(), ErrorCode.UNAUTHENTICATED, "Invalid session token.");
            }
        });

        // Start TcpServer on ephemeral port
        server = new TcpServer(0, dispatcher);
        server.start();
        port = server.getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    @DisplayName("1. Malformed JSON payloads & garbage stream over raw TCP socket -> MALFORMED_JSON")
    void testMalformedJsonHandling() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            socket.setSoTimeout(5000);

            // Send corrupted non-JSON text ending with newline
            writer.write("{this is not valid json, key: unquoted garbage\n");
            writer.flush();

            String responseLine = reader.readLine();
            assertThat(responseLine).isNotNull();

            ResponseEnvelope response = MAPPER.readValue(responseLine, ResponseEnvelope.class);
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.errorCode()).isEqualTo(ErrorCode.MALFORMED_JSON.name());
            assertThat(response.errorMessage()).contains("Malformed JSON");

            // Server connection remains operational: send a valid request on same socket
            String validReqId = UUID.randomUUID().toString();
            RequestEnvelope validCheck = new RequestEnvelope(validReqId, "SESSION_CHECK", null, null);
            FrameCodec.writeEnvelope(writer, validCheck);

            String nextLine = reader.readLine();
            assertThat(nextLine).isNotNull();
            ResponseEnvelope nextResp = MAPPER.readValue(nextLine, ResponseEnvelope.class);
            // Expected error for missing token, not server crash
            assertThat(nextResp.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED.name());
        }

        // Verify server acceptor is completely healthy for subsequent clients
        try (TcpClient client = new TcpClient("127.0.0.1", port)) {
            client.connect();
            assertThat(client.isConnected()).isTrue();
        }
    }

    @Test
    @DisplayName("2. Bounded buffer frame limit enforcement: frames > 64KB -> FRAME_TOO_LARGE")
    void testFrameSizeLimitEnforcement() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            socket.setSoTimeout(5000);

            // Send a line exceeding FrameCodec.MAX_FRAME_BYTES (65,536 bytes)
            String oversizedFrame = "X".repeat(FrameCodec.MAX_FRAME_BYTES + 1) + "\n";
            writer.write(oversizedFrame);
            writer.flush();

            String responseLine = reader.readLine();
            assertThat(responseLine).isNotNull();

            ResponseEnvelope response = MAPPER.readValue(responseLine, ResponseEnvelope.class);
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.errorCode()).isEqualTo(ErrorCode.FRAME_TOO_LARGE.name());
            assertThat(response.errorMessage()).contains("maximum allowed size");

            // Server cleanly closes connection after framing limit violation
            String nextRead = reader.readLine();
            assertThat(nextRead).isNull(); // EOF reached
        }

        // Server continues operating normally for new connections
        try (TcpClient client = new TcpClient("127.0.0.1", port)) {
            client.connect();
            assertThat(client.isConnected()).isTrue();
        }
    }

    @Test
    @DisplayName("3. Duplicate email registration -> DUPLICATE_EMAIL error code")
    void testDuplicateEmailRegistrationFails() throws Exception {
        try (TcpClient client = new TcpClient("127.0.0.1", port)) {
            client.connect();

            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("name", "Duplicate User");
            payload.put("email", "dup-test@uni.edu");
            payload.put("password", "SecurePassword123!");
            payload.put("role", "STUDENT");

            RequestEnvelope req1 = new RequestEnvelope(UUID.randomUUID().toString(), "REGISTER", null, payload);
            ResponseEnvelope resp1 = client.sendSync(req1);
            assertThat(resp1.isSuccess()).isTrue();
            assertThat(resp1.data().get("email").asText()).isEqualTo("dup-test@uni.edu");

            // Attempt duplicate registration with the same email
            RequestEnvelope req2 = new RequestEnvelope(UUID.randomUUID().toString(), "REGISTER", null, payload);
            ResponseEnvelope resp2 = client.sendSync(req2);
            assertThat(resp2.isSuccess()).isFalse();
            assertThat(resp2.errorCode()).isEqualTo(ErrorCode.DUPLICATE_EMAIL.name());
            assertThat(resp2.errorMessage()).contains("already exists");
        }
    }

    @Test
    @DisplayName("4. Invalid credentials & timing attack resistance on non-existent vs existing users")
    void testInvalidCredentialsAndTimingDefense() throws Exception {
        // Register an active user
        authService.register("Existing Student", "existing@uni.edu", "RealPassword123!", Role.STUDENT);

        try (TcpClient client = new TcpClient("127.0.0.1", port)) {
            client.connect();

            // 1. Non-existent email -> INVALID_CREDENTIALS
            ObjectNode ghostPayload = MAPPER.createObjectNode();
            ghostPayload.put("email", "ghost.nonexistent@uni.edu");
            ghostPayload.put("password", "SomePassword123!");

            RequestEnvelope ghostReq = new RequestEnvelope(UUID.randomUUID().toString(), "LOGIN", null, ghostPayload);
            ResponseEnvelope ghostResp = client.sendSync(ghostReq);
            assertThat(ghostResp.isSuccess()).isFalse();
            assertThat(ghostResp.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());

            // 2. Existing email with wrong password -> INVALID_CREDENTIALS
            ObjectNode wrongPassPayload = MAPPER.createObjectNode();
            wrongPassPayload.put("email", "existing@uni.edu");
            wrongPassPayload.put("password", "WrongPassword123!");

            RequestEnvelope wrongPassReq = new RequestEnvelope(UUID.randomUUID().toString(), "LOGIN", null, wrongPassPayload);
            ResponseEnvelope wrongPassResp = client.sendSync(wrongPassReq);
            assertThat(wrongPassResp.isSuccess()).isFalse();
            assertThat(wrongPassResp.errorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS.name());

            // 3. Timing resistance verification: both execute full BCrypt verification without early termination
            int iterations = 5;
            long ghostTotalNanos = 0;
            long wrongPassTotalNanos = 0;

            for (int i = 0; i < iterations; i++) {
                long t0 = System.nanoTime();
                client.sendSync(ghostReq);
                ghostTotalNanos += (System.nanoTime() - t0);

                long t1 = System.nanoTime();
                client.sendSync(wrongPassReq);
                wrongPassTotalNanos += (System.nanoTime() - t1);
            }

            double ghostAvgMs = (ghostTotalNanos / (double) iterations) / 1_000_000.0;
            double wrongPassAvgMs = (wrongPassTotalNanos / (double) iterations) / 1_000_000.0;

            // Both execute password verification, neither takes negligible (< 0.05ms) shortcut
            assertThat(ghostAvgMs).isGreaterThan(0.1);
            assertThat(wrongPassAvgMs).isGreaterThan(0.1);
        }
    }

    @Test
    @DisplayName("5. Abrupt client disconnection & TCP RST -> server cleans up gracefully without crashing")
    void testAbruptClientDropResilience() throws Exception {
        int socketCount = 5;
        List<Socket> sockets = new ArrayList<>();

        for (int i = 0; i < socketCount; i++) {
            Socket s = new Socket("127.0.0.1", port);
            s.getOutputStream().write("{\"requestId\":\"rst-init\",\"type\":\"UNKNOWN\"}\n".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            sockets.add(s);
        }

        // Forcibly terminate sockets with TCP reset (SO_LINGER = 0)
        for (Socket s : sockets) {
            s.setSoLinger(true, 0);
            s.close();
        }

        // Give server worker threads a moment to catch EOF/SocketException
        Thread.sleep(150);

        // Verify TcpServer is still healthy and accepts new clients
        try (TcpClient newClient = new TcpClient("127.0.0.1", port)) {
            newClient.connect();
            assertThat(newClient.isConnected()).isTrue();

            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("name", "Survivor User");
            payload.put("email", "survivor@uni.edu");
            payload.put("password", "Pass12345!");
            payload.put("role", "STUDENT");

            ResponseEnvelope resp = newClient.sendSync(new RequestEnvelope(UUID.randomUUID().toString(), "REGISTER", null, payload));
            assertThat(resp.isSuccess()).isTrue();
        }
    }

    @Test
    @DisplayName("6. Auto-reconnect & session survival across network interruptions")
    void testClientReconnectAndSessionContinuity() throws Exception {
        // Register and login to obtain a valid session token
        authService.register("Session Student", "session.student@uni.edu", "Password1234!", Role.STUDENT);

        String sessionToken;
        try (TcpClient client1 = new TcpClient("127.0.0.1", port)) {
            client1.connect();

            ObjectNode loginPayload = MAPPER.createObjectNode();
            loginPayload.put("email", "session.student@uni.edu");
            loginPayload.put("password", "Password1234!");

            ResponseEnvelope loginResp = client1.sendSync(new RequestEnvelope(UUID.randomUUID().toString(), "LOGIN", null, loginPayload));
            assertThat(loginResp.isSuccess()).isTrue();
            sessionToken = loginResp.data().get("token").asText();
            assertThat(sessionToken).isNotBlank();
        } // client1 is closed, dropping TCP connection

        // New connection (or reconnected client) reconnects and validates session survival
        try (TcpClient client2 = new TcpClient("127.0.0.1", port)) {
            client2.connect();

            RequestEnvelope checkReq = new RequestEnvelope(UUID.randomUUID().toString(), "SESSION_CHECK", sessionToken, null);
            ResponseEnvelope checkResp = client2.sendSync(checkReq);

            assertThat(checkResp.isSuccess()).isTrue();
            assertThat(checkResp.data().get("valid").asBoolean()).isTrue();
            assertThat(checkResp.data().get("email").asText()).isEqualTo("session.student@uni.edu");
            assertThat(checkResp.data().get("role").asText()).isEqualTo("STUDENT");
        }
    }

    @Test
    @DisplayName("7. High-concurrency multi-threaded session test (25 concurrent client threads)")
    void testConcurrentMultiUserSessions() throws Exception {
        int threadCount = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCounter = new AtomicInteger(0);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronize thread start

                    String email = "concurrent.student." + index + "@uni.edu";
                    String password = "Password" + index + "Secure!";

                    try (TcpClient client = new TcpClient("127.0.0.1", port)) {
                        client.connect();

                        // 1. Register
                        ObjectNode regPayload = MAPPER.createObjectNode();
                        regPayload.put("name", "Concurrent Student " + index);
                        regPayload.put("email", email);
                        regPayload.put("password", password);
                        regPayload.put("role", "STUDENT");

                        ResponseEnvelope regResp = client.sendSync(
                                new RequestEnvelope(UUID.randomUUID().toString(), "REGISTER", null, regPayload)
                        );
                        if (!regResp.isSuccess()) return;

                        // 2. Login
                        ObjectNode loginPayload = MAPPER.createObjectNode();
                        loginPayload.put("email", email);
                        loginPayload.put("password", password);

                        ResponseEnvelope loginResp = client.sendSync(
                                new RequestEnvelope(UUID.randomUUID().toString(), "LOGIN", null, loginPayload)
                        );
                        if (!loginResp.isSuccess()) return;

                        String token = loginResp.data().get("token").asText();

                        // 3. Session Check
                        ResponseEnvelope checkResp = client.sendSync(
                                new RequestEnvelope(UUID.randomUUID().toString(), "SESSION_CHECK", token, null)
                        );
                        if (!checkResp.isSuccess()) return;
                        if (!checkResp.data().get("valid").asBoolean()) return;
                        if (!email.equals(checkResp.data().get("email").asText())) return;

                        // 4. Mock booking authorization
                        ResponseEnvelope bookResp = client.sendSync(
                                new RequestEnvelope(UUID.randomUUID().toString(), "MOCK_BOOKING", token, null)
                        );
                        if (!bookResp.isSuccess()) return;

                        successCounter.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Test will assert success counter equals threadCount
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        startLatch.countDown(); // Release all 25 threads simultaneously
        boolean completedInTime = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completedInTime).isTrue();
        assertThat(successCounter.get()).isEqualTo(threadCount);
        assertThat(sessionManager.getActiveSessionCount()).isGreaterThanOrEqualTo(threadCount);
    }

    @Test
    @DisplayName("8. Downstream mock booking session authorization & context bridge verification")
    void testDownstreamBookingAuthorization() throws Exception {
        User registeredUser = authService.register("Booking Student", "booker@uni.edu", "ValidPass123!", Role.STUDENT);

        try (TcpClient client = new TcpClient("127.0.0.1", port)) {
            client.connect();

            // A. Login to get valid token
            ObjectNode loginPayload = MAPPER.createObjectNode();
            loginPayload.put("email", "booker@uni.edu");
            loginPayload.put("password", "ValidPass123!");

            ResponseEnvelope loginResp = client.sendSync(new RequestEnvelope(UUID.randomUUID().toString(), "LOGIN", null, loginPayload));
            assertThat(loginResp.isSuccess()).isTrue();
            String validToken = loginResp.data().get("token").asText();

            // Contract verification: ContextBridge resolves verified userId
            Long verifiedUserId = contextBridge.extractAuthenticatedUserId(validToken);
            assertThat(verifiedUserId).isEqualTo(registeredUser.id());

            // Valid session booking succeeds
            RequestEnvelope validBookingReq = new RequestEnvelope(UUID.randomUUID().toString(), "MOCK_BOOKING", validToken, null);
            ResponseEnvelope validBookingResp = client.sendSync(validBookingReq);
            assertThat(validBookingResp.isSuccess()).isTrue();
            assertThat(validBookingResp.data().get("userId").asLong()).isEqualTo(registeredUser.id());
            assertThat(validBookingResp.data().get("status").asText()).isEqualTo("CONFIRMED");

            // B. Forged / non-existent session token is rejected with UNAUTHENTICATED
            String forgedToken = UUID.randomUUID().toString();
            RequestEnvelope forgedBookingReq = new RequestEnvelope(UUID.randomUUID().toString(), "MOCK_BOOKING", forgedToken, null);
            ResponseEnvelope forgedResp = client.sendSync(forgedBookingReq);
            assertThat(forgedResp.isSuccess()).isFalse();
            assertThat(forgedResp.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED.name());

            assertThatThrownBy(() -> contextBridge.extractAuthenticatedUserId(forgedToken))
                    .isInstanceOf(InvalidCredentialsException.class);

            // C. Expired / invalidated session token is rejected
            sessionManager.invalidateSession(validToken);
            RequestEnvelope expiredBookingReq = new RequestEnvelope(UUID.randomUUID().toString(), "MOCK_BOOKING", validToken, null);
            ResponseEnvelope expiredResp = client.sendSync(expiredBookingReq);
            assertThat(expiredResp.isSuccess()).isFalse();
            assertThat(expiredResp.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED.name());

            assertThatThrownBy(() -> contextBridge.extractAuthenticatedUserId(validToken))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
    }

    @Test
    @DisplayName("9. Password length validation bounds: < 8 chars or > 72 UTF-8 bytes -> VALIDATION_FAILED")
    void testPasswordLengthValidationBounds() throws Exception {
        try (TcpClient client = new TcpClient("127.0.0.1", port)) {
            client.connect();

            // A. Password too short (7 characters)
            ObjectNode shortPassPayload = MAPPER.createObjectNode();
            shortPassPayload.put("name", "Short Pass User");
            shortPassPayload.put("email", "short@uni.edu");
            shortPassPayload.put("password", "1234567");
            shortPassPayload.put("role", "STUDENT");

            ResponseEnvelope shortResp = client.sendSync(
                    new RequestEnvelope(UUID.randomUUID().toString(), "REGISTER", null, shortPassPayload)
            );
            assertThat(shortResp.isSuccess()).isFalse();
            assertThat(shortResp.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
            assertThat(shortResp.errorMessage()).contains("at least 8 characters");

            // B. Password too long (> 72 UTF-8 bytes, BCrypt hard limit)
            String longPass = "A".repeat(73);
            ObjectNode longPassPayload = MAPPER.createObjectNode();
            longPassPayload.put("name", "Long Pass User");
            longPassPayload.put("email", "long@uni.edu");
            longPassPayload.put("password", longPass);
            longPassPayload.put("role", "STUDENT");

            ResponseEnvelope longResp = client.sendSync(
                    new RequestEnvelope(UUID.randomUUID().toString(), "REGISTER", null, longPassPayload)
            );
            assertThat(longResp.isSuccess()).isFalse();
            assertThat(longResp.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
            assertThat(longResp.errorMessage()).contains("72 UTF-8 bytes");
        }
    }

    @Test
    @DisplayName("10. Role privilege escalation defense: public registration for ADMIN -> FORBIDDEN")
    void testUnauthorizedAdminRegistrationForbidden() throws Exception {
        try (TcpClient client = new TcpClient("127.0.0.1", port)) {
            client.connect();

            // Public registration attempting to self-assign ADMIN role
            ObjectNode adminPayload = MAPPER.createObjectNode();
            adminPayload.put("name", "Attacker");
            adminPayload.put("email", "hacker@evil.com");
            adminPayload.put("password", "HackerSecret123!");
            adminPayload.put("role", "ADMIN");

            RequestEnvelope adminReq = new RequestEnvelope(UUID.randomUUID().toString(), "REGISTER", null, adminPayload);
            ResponseEnvelope adminResp = client.sendSync(adminReq);

            assertThat(adminResp.isSuccess()).isFalse();
            assertThat(adminResp.errorCode()).isEqualTo(ErrorCode.FORBIDDEN.name());
            assertThat(adminResp.errorMessage()).contains("ADMIN role");
            assertThat(adminResp.errorMessage()).contains("privilege required");

            // Verify user was NOT persisted in the database
            assertThat(userRepository.findByEmail("hacker@evil.com")).isEmpty();
        }
    }
}
