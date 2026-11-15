package com.unievents.unievents_backend;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:auth;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.sql.init.mode=never"
})
class AuthIntegrationTests {
    @LocalServerPort int port;
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate db;
    final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/" + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path, String body, boolean csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/" + path))
            .header("Content-Type", "application/json");
        if (csrf) {
            var match = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(get("csrf").body());
            assertTrue(match.find());
            builder.header("X-CSRF-TOKEN", match.group(1));
        }
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void registerLoginSessionAndLogout() throws Exception {
        db.execute("CREATE TABLE IF NOT EXISTS app_users(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR(100), email VARCHAR(254) UNIQUE, password_hash VARCHAR(255))");
        assertEquals(401, get("me").statusCode());
        assertEquals(403, post("login", "{}", false).statusCode());
        assertEquals(400, post("register", "{\"name\":\"\",\"email\":\"invalid\",\"password\":\"short\"}", true).statusCode());
        String credentials = "{\"name\":\"Student\",\"email\":\"student@example.com\",\"password\":\"test-password-123\"}";
        assertEquals(201, post("register", credentials, true).statusCode());
        assertTrue(get("me").body().contains("student@example.com"));
        assertEquals(409, post("register", credentials, true).statusCode());
        assertEquals(204, post("logout", "{}", true).statusCode());
        assertEquals(401, get("me").statusCode());
        assertEquals(401, post("login", credentials.replace("test-password-123", "incorrect"), true).statusCode());
        assertEquals(200, post("login", credentials, true).statusCode());
        assertEquals(200, get("me").statusCode());
        String hash = db.queryForObject("SELECT password_hash FROM app_users WHERE email='student@example.com'", String.class);
        assertNotEquals("test-password-123", hash);
        assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches("test-password-123", hash));
    }
}
