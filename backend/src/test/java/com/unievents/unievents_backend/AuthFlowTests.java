package com.unievents.unievents_backend;

import com.unievents.unievents_backend.auth.AuthController;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthFlowTests {
    @Test
    void unknownUserCannotSignIn() {
        var db = mock(JdbcTemplate.class);
        var controller = new AuthController(db, new BCryptPasswordEncoder(4));
        var request = new MockHttpServletRequest();
        var error = assertThrows(ResponseStatusException.class, () -> controller.login(
            new AuthController.Login("missing@example.com", "wrong-password"), request, new MockHttpServletResponse()));
        assertEquals(401, error.getStatusCode().value());
        assertNull(request.getSession(false));
    }

    @Test
    void rejectsPasswordsExceedingBcryptByteLimitBeforeDatabaseAccess() {
        var db = mock(JdbcTemplate.class);
        var controller = new AuthController(db, new BCryptPasswordEncoder(4));
        var error = assertThrows(ResponseStatusException.class, () -> controller.register(
            new AuthController.Registration("Student", "student@example.com", "é".repeat(40)),
            new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(db);
    }
}
