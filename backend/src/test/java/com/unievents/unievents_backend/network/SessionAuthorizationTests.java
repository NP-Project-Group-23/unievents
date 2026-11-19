package com.unievents.unievents_backend.network;

import com.unievents.unievents_backend.domain.model.*;
import com.unievents.unievents_backend.domain.service.*;
import com.unievents.unievents_backend.handlers.RegisterHandler;
import com.unievents.unievents_backend.network.protocol.*;
import com.unievents.unievents_backend.network.server.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionAuthorizationTests {
    private final ConnectionContext stale = new ConnectionContext("c",1L,"admin@example.com","ADMIN",null);
    private User admin() { return new User(1L,"Admin","admin@example.com","hash",Role.ADMIN,Instant.now()); }

    @Test
    void missingInvalidAndRevokedTokensCannotReuseIdentity() {
        var sessions = new SessionManager();
        var dispatcher = new RequestDispatcher(sessions);
        var handler = mock(RequestHandler.class);
        dispatcher.registerHandler("BOOK_SEAT", handler);
        String token = sessions.createSession(admin()).token();
        sessions.invalidateSession(token);
        for (String value : new String[] {null,"invalid",token}) {
            assertEquals("UNAUTHENTICATED", dispatcher.dispatch(
                RequestEnvelope.of("BOOK_SEAT", value, null), stale).errorCode());
        }
        verifyNoInteractions(handler);
    }

    @Test
    void expiredSessionIsRejectedAndRemoved() {
        var sessions = new SessionManager(Duration.ofSeconds(-1));
        var dispatcher = new RequestDispatcher(sessions);
        var handler = mock(RequestHandler.class);
        dispatcher.registerHandler("GET_METRICS",handler);
        String token = sessions.createSession(admin()).token();
        assertEquals("SESSION_EXPIRED", dispatcher.dispatch(RequestEnvelope.of("GET_METRICS",token,null),stale).errorCode());
        assertTrue(sessions.validateSession(token).isEmpty());
        verifyNoInteractions(handler);
    }

    @Test
    void adminRegistrationCannotTrustForgedContext() {
        var auth = mock(StudentAuthService.class);
        var handler = new RegisterHandler(auth, new SessionManager());
        var payload = new ObjectMapper().createObjectNode().put("role","ADMIN");
        assertEquals("FORBIDDEN",handler.handle(RequestEnvelope.of("REGISTER",payload),stale).errorCode());
        verifyNoInteractions(auth);
    }
}
