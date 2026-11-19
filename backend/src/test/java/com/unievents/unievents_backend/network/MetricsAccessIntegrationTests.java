package com.unievents.unievents_backend.network;

import com.unievents.unievents_backend.domain.model.*;
import com.unievents.unievents_backend.domain.service.SessionManager;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.server.TcpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:metrics-access;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:booking-users.sql"
})
class MetricsAccessIntegrationTests {
    @Autowired TcpServer server;
    @Autowired SessionManager sessions;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder passwords;
    @LocalServerPort int port;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void tcpMetricsRequiresAdminAndDoesNotRetainSocketIdentity() throws Exception {
        String admin = sessions.createSession(new User(1L,"Admin","admin@example.com","hash",Role.ADMIN,Instant.now())).token();
        String student = sessions.createSession(new User(2L,"Student","student@example.com","hash",Role.STUDENT,Instant.now())).token();
        try (var client = new TcpClient("127.0.0.1",server.getPort(),Duration.ofSeconds(5),false)) {
            client.connect();
            assertEquals("UNAUTHENTICATED",client.sendSync(RequestEnvelope.of("GET_METRICS",null)).errorCode());
            assertEquals("FORBIDDEN",client.sendSync(RequestEnvelope.of("GET_METRICS",student,null)).errorCode());
            var request = RequestEnvelope.of("GET_METRICS",admin,null);
            var response = client.sendSync(request);
            assertTrue(response.isSuccess());
            assertEquals(request.requestId(), response.requestId());
            assertTrue(response.data().path("activeConnections").asLong() >= 1);
            assertTrue(response.data().path("totalSubmittedTasks").asLong() >= 1);
            assertNotNull(Instant.parse(response.data().path("timestamp").asText()));
            assertFalse(JSON.valueToTree(response).has("type"));
            assertEquals("UNAUTHENTICATED",client.sendSync(RequestEnvelope.of("GET_METRICS",null)).errorCode());
            sessions.invalidateSession(admin);
            assertEquals("UNAUTHENTICATED",client.sendSync(RequestEnvelope.of("GET_METRICS",admin,null)).errorCode());
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }

    private HttpClient login(String role) throws Exception {
        String email = role.toLowerCase()+"@metrics.example";
        db.update("INSERT INTO app_users(name,email,password_hash,role) VALUES (?,?,?,?)",role,email,passwords.encode("test-password"),role);
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        var csrf = JSON.readTree(get(client,"/api/auth/csrf").body());
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/login"))
            .header("Content-Type","application/json").header(csrf.path("headerName").asText(),csrf.path("token").asText())
            .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\""+email+"\",\"password\":\"test-password\"}"))
            .build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode());
        return client;
    }

    @Test
    void httpMetricsAliasesRequireAuthenticatedAdmin() throws Exception {
        var anonymous = HttpClient.newHttpClient();
        var student = login("STUDENT");
        var admin = login("ADMIN");
        for (String root : new String[]{"/api/admin/metrics","/api/metrics"}) {
            for (String suffix : new String[]{"","/events","/dashboard"}) {
                assertEquals(401,get(anonymous,root+suffix).statusCode());
                assertEquals(403,get(student,root+suffix).statusCode());
                assertEquals(200,get(admin,root+suffix).statusCode());
            }
        }
    }
}
