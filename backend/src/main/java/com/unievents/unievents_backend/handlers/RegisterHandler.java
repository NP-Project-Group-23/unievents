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
    private final com.unievents.unievents_backend.domain.service.SessionManager sessionManager;

    public RegisterHandler(StudentAuthService authService) {
        this(authService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RegisterHandler(
            StudentAuthService authService,
            @org.springframework.lang.Nullable com.unievents.unievents_backend.domain.service.SessionManager sessionManager
    ) {
        this.authService = authService;
        this.sessionManager = sessionManager;
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

        Role assignedRole = Role.STUDENT;
        if (roleStr != null && !roleStr.isBlank()) {
            Role requestedRole = Role.fromString(roleStr);
            if (requestedRole == Role.ADMIN) {
                boolean isAdmin = false;
                if (context != null && "ADMIN".equalsIgnoreCase(context.role())) {
                    isAdmin = true;
                } else {
                    String token = resolveToken(request);
                    if (token != null && sessionManager != null && sessionManager.requireRole(token, Role.ADMIN)) {
                        isAdmin = true;
                    }
                }

                if (!isAdmin) {
                    return ResponseEnvelope.error(
                            request.requestId(),
                            ErrorCode.FORBIDDEN,
                            "Public registration cannot assign ADMIN role. Administrator privilege required."
                    );
                }
                assignedRole = Role.ADMIN;
            }
        }

        try {
            User user = authService.register(name, email, password, assignedRole);

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

    private String resolveToken(RequestEnvelope request) {
        if (request == null) {
            return null;
        }
        if (request.token() != null && !request.token().isBlank()) {
            return request.token().strip();
        }
        if (request.payload() != null && request.payload().hasNonNull("token")) {
            return request.payload().get("token").asText().strip();
        }
        return null;
    }
}
