package com.unievents.unievents_backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.model.User;
import com.unievents.unievents_backend.domain.repository.JdbcUserRepository;
import com.unievents.unievents_backend.domain.service.BCryptPasswordHasher;
import com.unievents.unievents_backend.domain.service.ContextBridge;
import com.unievents.unievents_backend.domain.service.SessionManager;
import com.unievents.unievents_backend.domain.service.StudentAuthService;
import com.unievents.unievents_backend.handlers.*;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
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

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AuthHandlersIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SingleConnectionDataSource dataSource;
    private TcpServer server;
    private TcpClient client;
    private int port;

    private JdbcUserRepository userRepository;
    private SessionManager sessionManager;
    private StudentAuthService authService;
    private ContextBridge contextBridge;

    @BeforeEach
    void setUp() throws Exception {
        // Setup isolated in-memory DB
        String dbName = "auth_integration_" + UUID.randomUUID().toString().replace("-", "");
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
        BCryptPasswordHasher passwordHasher = new BCryptPasswordHasher(4);
        sessionManager = new SessionManager(Duration.ofMinutes(30));
        authService = new StudentAuthService(userRepository, passwordHasher, sessionManager);
        contextBridge = new ContextBridge(sessionManager);

        RequestDispatcher dispatcher = new RequestDispatcher(sessionManager);
        AuthHandlersConfig.registerHandlers(
                dispatcher,
                new RegisterHandler(authService, sessionManager),
                new LoginHandler(authService),
                new LogoutHandler(sessionManager),
                new SessionCheckHandler(sessionManager)
        );

        // Context inspector handler for verifying connection context enrichment
        dispatcher.registerHandler("INSPECT_CONTEXT", (request, context) -> {
            ObjectNode data = MAPPER.createObjectNode();
            data.put("authenticated", context != null && context.isAuthenticated());
            if (context != null && context.isAuthenticated()) {
                data.put("userId", context.userId());
                data.put("email", context.email());
                data.put("role", context.role());
            }
            return ResponseEnvelope.success(request.requestId(), data);
        });

        // Downstream BookingService simulation consuming contextBridge
        dispatcher.registerHandler("BOOK_SEAT", (request, context) -> {
            String token = request.token();
            try {
                Long verifiedUserId = contextBridge.extractAuthenticatedUserId(token);
                ObjectNode data = MAPPER.createObjectNode();
                data.put("bookingId", 999L);
                data.put("userId", verifiedUserId);
                data.put("reference", "UE-BOOKING-SUCCESS");
                data.put("status", "CONFIRMED");
                return ResponseEnvelope.success(request.requestId(), data);
            } catch (Exception e) {
                return ResponseEnvelope.error(request.requestId(), ErrorCode.UNAUTHENTICATED, "Authentication required for booking");
            }
        });

        // Start server on ephemeral port (port 0)
        server = new TcpServer(0, dispatcher);
        server.start();
        port = server.getPort();

        client = new TcpClient("127.0.0.1", port);
        client.connect();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    @DisplayName("full authentication and downstream booking lifecycle over raw TCP socket")
    void testFullAuthLifecycleAndDownstreamIntegration() throws Exception {
        // 1. REGISTER
        ObjectNode regPayload = MAPPER.createObjectNode();
        regPayload.put("name", "Jane Student");
        regPayload.put("email", "jane.student@uni.edu");
        regPayload.put("password", "strongPassword123");
        regPayload.put("role", "STUDENT");

        RequestEnvelope regRequest = RequestEnvelope.of("REGISTER", regPayload);
        ResponseEnvelope regResponse = client.sendSync(regRequest);

        assertNotNull(regResponse);
        assertTrue(regResponse.isSuccess(), "Registration should succeed: " + regResponse.errorMessage());
        assertNotNull(regResponse.data());
        assertTrue(regResponse.data().get("id").asLong() > 0);
        assertEquals("Jane Student", regResponse.data().get("name").asText());
        assertEquals("jane.student@uni.edu", regResponse.data().get("email").asText());
        assertEquals("STUDENT", regResponse.data().get("role").asText());

        Long userId = regResponse.data().get("id").asLong();

        // 2. DUPLICATE REGISTER
        ResponseEnvelope dupResponse = client.sendSync(regRequest);
        assertTrue(dupResponse.isError());
        assertEquals(ErrorCode.DUPLICATE_EMAIL.name(), dupResponse.errorCode());

        // 3. LOGIN WITH INVALID PASSWORD
        ObjectNode badLoginPayload = MAPPER.createObjectNode();
        badLoginPayload.put("email", "jane.student@uni.edu");
        badLoginPayload.put("password", "wrongPassword");

        ResponseEnvelope badLoginResponse = client.sendSync(RequestEnvelope.of("LOGIN", badLoginPayload));
        assertTrue(badLoginResponse.isError());
        assertEquals(ErrorCode.INVALID_CREDENTIALS.name(), badLoginResponse.errorCode());

        // 4. LOGIN WITH VALID CREDENTIALS
        ObjectNode loginPayload = MAPPER.createObjectNode();
        loginPayload.put("email", "jane.student@uni.edu");
        loginPayload.put("password", "strongPassword123");

        ResponseEnvelope loginResponse = client.sendSync(RequestEnvelope.of("LOGIN", loginPayload));
        assertTrue(loginResponse.isSuccess(), "Login should succeed: " + loginResponse.errorMessage());
        assertNotNull(loginResponse.data());
        assertTrue(loginResponse.data().hasNonNull("token"));
        assertEquals(userId, loginResponse.data().get("userId").asLong());
        assertEquals("STUDENT", loginResponse.data().get("role").asText());

        String sessionToken = loginResponse.data().get("token").asText();

        // 5. SESSION_CHECK WITH VALID TOKEN
        RequestEnvelope sessionCheckReq = RequestEnvelope.of("SESSION_CHECK", sessionToken, null);
        ResponseEnvelope sessionCheckResp = client.sendSync(sessionCheckReq);
        assertTrue(sessionCheckResp.isSuccess());
        assertTrue(sessionCheckResp.data().get("valid").asBoolean());
        assertEquals(userId, sessionCheckResp.data().get("userId").asLong());
        assertEquals("STUDENT", sessionCheckResp.data().get("role").asText());

        // 6. DOWNSTREAM BOOKING WITH AUTHENTICATED SESSION TOKEN
        RequestEnvelope bookReq = RequestEnvelope.of("BOOK_SEAT", sessionToken, null);
        ResponseEnvelope bookResp = client.sendSync(bookReq);
        assertTrue(bookResp.isSuccess(), "Downstream booking should succeed with verified session");
        assertEquals(userId, bookResp.data().get("userId").asLong(), "Verified userId must match authenticated user");

        // 6b. DOWNSTREAM BOOKING WITHOUT TOKEN (SPOOFING / UNATHENTICATED)
        RequestEnvelope unauthBookReq = RequestEnvelope.of("BOOK_SEAT", "forged-invalid-token", null);
        ResponseEnvelope unauthBookResp = client.sendSync(unauthBookReq);
        assertTrue(unauthBookResp.isError());
        assertEquals(ErrorCode.UNAUTHENTICATED.name(), unauthBookResp.errorCode());

        // 7. LOGOUT
        RequestEnvelope logoutReq = RequestEnvelope.of("LOGOUT", sessionToken, null);
        ResponseEnvelope logoutResp = client.sendSync(logoutReq);
        assertTrue(logoutResp.isSuccess());
        assertEquals("Logged out successfully", logoutResp.data().get("message").asText());

        // 8. SESSION_CHECK AFTER LOGOUT
        ResponseEnvelope afterLogoutResp = client.sendSync(sessionCheckReq);
        assertTrue(afterLogoutResp.isError());
        assertEquals(ErrorCode.UNAUTHENTICATED.name(), afterLogoutResp.errorCode());

        // 9. LOGOUT AGAIN (TOKEN ALREADY INVALID)
        ResponseEnvelope logoutAgainResp = client.sendSync(logoutReq);
        assertTrue(logoutAgainResp.isError());
        assertEquals(ErrorCode.UNAUTHENTICATED.name(), logoutAgainResp.errorCode());
    }

    @Test
    @DisplayName("public registration attempting role ADMIN is blocked with FORBIDDEN")
    void testPublicRegisterCannotEscalateToAdmin() throws Exception {
        ObjectNode hackPayload = MAPPER.createObjectNode();
        hackPayload.put("name", "Adversary");
        hackPayload.put("email", "hacker@evil.com");
        hackPayload.put("password", "strongPassword123");
        hackPayload.put("role", "ADMIN");

        RequestEnvelope regRequest = RequestEnvelope.of("REGISTER", hackPayload);
        ResponseEnvelope regResponse = client.sendSync(regRequest);

        assertNotNull(regResponse);
        assertTrue(regResponse.isError(), "Public registration specifying ADMIN must be rejected");
        assertEquals(ErrorCode.FORBIDDEN.name(), regResponse.errorCode());
        assertFalse(userRepository.existsByEmail("hacker@evil.com"), "Hacker must not be persisted in database");
    }

    @Test
    @DisplayName("authenticated admin session can successfully register another admin user")
    void testAuthenticatedAdminCanRegisterAdminUser() throws Exception {
        // Create pre-existing admin in repository
        BCryptPasswordHasher hasher = new BCryptPasswordHasher(4);
        User adminUser = userRepository.save(new User(
                null,
                "Super Admin",
                "superadmin@uni.edu",
                hasher.hash("adminPass123"),
                Role.ADMIN,
                java.time.Instant.now()
        ));

        // Authenticate admin to obtain active session
        com.unievents.unievents_backend.domain.service.AuthResult auth = authService.authenticate("superadmin@uni.edu", "adminPass123");
        String adminToken = auth.token();

        // Register new admin using adminToken in request
        ObjectNode newAdminPayload = MAPPER.createObjectNode();
        newAdminPayload.put("name", "Secondary Admin");
        newAdminPayload.put("email", "secadmin@uni.edu");
        newAdminPayload.put("password", "newAdminPass123");
        newAdminPayload.put("role", "ADMIN");

        RequestEnvelope adminRegReq = RequestEnvelope.of("REGISTER", adminToken, newAdminPayload);
        ResponseEnvelope adminRegResp = client.sendSync(adminRegReq);

        assertNotNull(adminRegResp);
        assertTrue(adminRegResp.isSuccess(), "Admin registration by authenticated admin must succeed: " + adminRegResp.errorMessage());
        assertEquals("ADMIN", adminRegResp.data().get("role").asText());
        assertEquals("secadmin@uni.edu", adminRegResp.data().get("email").asText());

        User persisted = userRepository.findByEmail("secadmin@uni.edu").orElseThrow();
        assertEquals(Role.ADMIN, persisted.role());
    }

    @Test
    @DisplayName("ConnectionContext is enriched with authenticated userId, email, and role")
    void testConnectionContextEnrichmentAcrossRequests() throws Exception {
        // Initial state: unauthenticated
        RequestEnvelope unauthInspect = RequestEnvelope.of("INSPECT_CONTEXT", null);
        ResponseEnvelope unauthResp = client.sendSync(unauthInspect);
        assertTrue(unauthResp.isSuccess());
        assertFalse(unauthResp.data().get("authenticated").asBoolean(), "Connection should initially be unauthenticated");

        // Register student
        ObjectNode regPayload = MAPPER.createObjectNode();
        regPayload.put("name", "Context Student");
        regPayload.put("email", "context.student@uni.edu");
        regPayload.put("password", "pass123456");

        ResponseEnvelope regResp = client.sendSync(RequestEnvelope.of("REGISTER", regPayload));
        assertTrue(regResp.isSuccess());
        Long studentId = regResp.data().get("id").asLong();

        // Login student
        ObjectNode loginPayload = MAPPER.createObjectNode();
        loginPayload.put("email", "context.student@uni.edu");
        loginPayload.put("password", "pass123456");

        ResponseEnvelope loginResp = client.sendSync(RequestEnvelope.of("LOGIN", loginPayload));
        assertTrue(loginResp.isSuccess());
        String token = loginResp.data().get("token").asText();

        // Inspect context with token
        RequestEnvelope authInspect = RequestEnvelope.of("INSPECT_CONTEXT", token, null);
        ResponseEnvelope authResp = client.sendSync(authInspect);
        assertTrue(authResp.isSuccess());
        assertTrue(authResp.data().get("authenticated").asBoolean(), "ConnectionContext must be authenticated");
        assertEquals(studentId, authResp.data().get("userId").asLong());
        assertEquals("context.student@uni.edu", authResp.data().get("email").asText());
        assertEquals("STUDENT", authResp.data().get("role").asText());

        // Logout
        ResponseEnvelope logoutResp = client.sendSync(RequestEnvelope.of("LOGOUT", token, null));
        assertTrue(logoutResp.isSuccess());

        // Inspect context after logout
        ResponseEnvelope postLogoutResp = client.sendSync(RequestEnvelope.of("INSPECT_CONTEXT", null));
        assertTrue(postLogoutResp.isSuccess());
        assertFalse(postLogoutResp.data().get("authenticated").asBoolean(), "ConnectionContext must be unauthenticated after logout");
    }
}
