package com.unievents.unievents_backend.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.domain.service.AuthResult;
import com.unievents.unievents_backend.domain.service.InvalidCredentialsException;
import com.unievents.unievents_backend.domain.service.StudentAuthService;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import com.unievents.unievents_backend.network.server.ConnectionContext;
import com.unievents.unievents_backend.network.server.RequestHandler;
import org.springframework.stereotype.Component;

/**
 * TCP RequestHandler for the LOGIN operation.
 */
@Component
public class LoginHandler implements RequestHandler {

    public static final String VERB = "LOGIN";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StudentAuthService authService;

    public LoginHandler(StudentAuthService authService) {
        this.authService = authService;
    }

    @Override
    public ResponseEnvelope handle(RequestEnvelope request, ConnectionContext context) {
        JsonNode payload = request.payload();
        if (payload == null || !payload.isObject()) {
            return ResponseEnvelope.error(
                    request.requestId(),
                    ErrorCode.INVALID_CREDENTIALS,
                    "Payload must contain email and password."
            );
        }

        String email = payload.path("email").asText(null);
        String password = payload.path("password").asText(null);

        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return ResponseEnvelope.error(
                    request.requestId(),
                    ErrorCode.INVALID_CREDENTIALS,
                    "Invalid email or password."
            );
        }

        try {
            AuthResult authResult = authService.authenticate(email, password);

            ObjectNode data = MAPPER.createObjectNode();
            data.put("token", authResult.token());
            data.put("userId", authResult.user().id());
            data.put("name", authResult.user().name());
            data.put("email", authResult.user().email());
            data.put("role", authResult.user().role().name());

            return ResponseEnvelope.success(request.requestId(), data);
        } catch (InvalidCredentialsException e) {
            return ResponseEnvelope.error(request.requestId(), ErrorCode.INVALID_CREDENTIALS, e.getMessage());
        } catch (Exception e) {
            return ResponseEnvelope.error(request.requestId(), ErrorCode.INTERNAL_ERROR, "Login processing error: " + e.getMessage());
        }
    }
}
