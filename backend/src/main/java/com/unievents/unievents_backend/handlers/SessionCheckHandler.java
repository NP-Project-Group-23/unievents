package com.unievents.unievents_backend.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.domain.service.SessionContext;
import com.unievents.unievents_backend.domain.service.SessionManager;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import com.unievents.unievents_backend.network.server.ConnectionContext;
import com.unievents.unievents_backend.network.server.RequestHandler;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * TCP RequestHandler for the SESSION_CHECK operation.
 */
@Component
public class SessionCheckHandler implements RequestHandler {

    public static final String VERB = "SESSION_CHECK";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionManager sessionManager;

    public SessionCheckHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public ResponseEnvelope handle(RequestEnvelope request, ConnectionContext context) {
        String token = resolveToken(request);

        if (token == null || token.isBlank()) {
            return ResponseEnvelope.error(
                    request.requestId(),
                    ErrorCode.UNAUTHENTICATED,
                    "Session token is required."
            );
        }

        if (sessionManager.isExpired(token)) {
            sessionManager.invalidateSession(token);
            return ResponseEnvelope.error(
                    request.requestId(),
                    ErrorCode.SESSION_EXPIRED,
                    "Session has expired."
            );
        }

        Optional<SessionContext> sessionOptional = sessionManager.validateSession(token);
        if (sessionOptional.isEmpty()) {
            return ResponseEnvelope.error(
                    request.requestId(),
                    ErrorCode.UNAUTHENTICATED,
                    "Invalid or inactive session token."
            );
        }

        SessionContext session = sessionOptional.get();
        ObjectNode data = MAPPER.createObjectNode();
        data.put("valid", true);
        data.put("token", session.token());
        data.put("userId", session.userId());
        data.put("email", session.email());
        data.put("role", session.role().name());

        return ResponseEnvelope.success(request.requestId(), data);
    }

    private String resolveToken(RequestEnvelope request) {
        if (request.token() != null && !request.token().isBlank()) {
            return request.token().strip();
        }
        if (request.payload() != null && request.payload().hasNonNull("token")) {
            return request.payload().get("token").asText().strip();
        }
        return null;
    }
}
