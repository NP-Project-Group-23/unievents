package com.unievents.unievents_backend;

import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.repository.UserRepository;
import com.unievents.unievents_backend.network.server.TcpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:local-runtime-smoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "unievents.tcp.port=0"
})
class LocalRuntimeSmokeTests {
    @Autowired TcpServer server;
    @Autowired UserRepository users;

    @Test
    void localProfileMigratesStartsTcpAndBootstrapsAdministrator() {
        assertTrue(server.isRunning());
        assertTrue(server.getPort() > 0);
        var admin = users.findByEmail("admin@unievents.local").orElseThrow();
        assertEquals(Role.ADMIN, admin.role());
        assertFalse(admin.passwordHash().isBlank());
    }
}
