package com.unievents.unievents_backend.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.domain.service.SessionManager;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import com.unievents.unievents_backend.network.server.ConnectionContext;
import com.unievents.unievents_backend.network.server.RequestHandler;
import org.springframework.stereotype.Component;

/**
 * TCP RequestHandler for the LOGOUT operation.
 */
@Component
public class LogoutHandler implements RequestHandler {

    public static final String VERB = "LOGOUT";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionManager sessionManager;

    public LogoutHandler(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public ResponseEnvelope handle(RequestEnvelope request, ConnectionContext context) {
        String token = resolveToken(request);

        if (token == null || token.isBlank()) {
            return ResponseEnvelope.error(
                    request.requestId(),
                    ErrorCode.UNAUTHENTICATED,
                    "Session token is missing."
            );
        }

        boolean removed = sessionManager.invalidateSession(token);
        if (!removed) {
            return ResponseEnvelope.error(
                    request.requestId(),
                    ErrorCode.UNAUTHENTICATED,
                    "No active session found for the provided token."
            );
        }

        ObjectNode data = MAPPER.createObjectNode();
        data.put("message", "Logged out successfully");
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
