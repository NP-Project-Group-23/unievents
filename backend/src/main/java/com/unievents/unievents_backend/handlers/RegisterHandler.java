package com.unievents.unievents_backend.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.model.User;
import com.unievents.unievents_backend.domain.service.AuthValidationException;
import com.unievents.unievents_backend.domain.service.DuplicateEmailException;
import com.unievents.unievents_backend.domain.service.StudentAuthService;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import com.unievents.unievents_backend.network.server.ConnectionContext;
import com.unievents.unievents_backend.network.server.RequestHandler;
import org.springframework.stereotype.Component;

/**
 * TCP RequestHandler for the REGISTER operation.
 */
@Component
public class RegisterHandler implements RequestHandler {

    public static final String VERB = "REGISTER";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final StudentAuthService authService;

    public RegisterHandler(StudentAuthService authService) {
        this.authService = authService;
    }

    @Override
    public ResponseEnvelope handle(RequestEnvelope request, ConnectionContext context) {
        JsonNode payload = request.payload();
        if (payload == null || !payload.isObject()) {
            return ResponseEnvelope.error(
                    request.requestId(),
                    ErrorCode.VALIDATION_FAILED,
                    "Payload must be a valid JSON object."
            );
        }

        String name = payload.path("name").asText(null);
        String email = payload.path("email").asText(null);
        String password = payload.path("password").asText(null);
        String roleStr = payload.path("role").asText(null);

        Role role = Role.fromString(roleStr);

        try {
            User user = authService.register(name, email, password, role);

            ObjectNode data = MAPPER.createObjectNode();
            data.put("id", user.id());
            data.put("name", user.name());
            data.put("email", user.email());
            data.put("role", user.role().name());

            return ResponseEnvelope.success(request.requestId(), data);
        } catch (DuplicateEmailException e) {
            return ResponseEnvelope.error(request.requestId(), ErrorCode.DUPLICATE_EMAIL, e.getMessage());
        } catch (AuthValidationException e) {
            return ResponseEnvelope.error(request.requestId(), ErrorCode.VALIDATION_FAILED, e.getMessage());
        } catch (Exception e) {
            return ResponseEnvelope.error(request.requestId(), ErrorCode.INTERNAL_ERROR, "Registration failed: " + e.getMessage());
        }
    }
}
