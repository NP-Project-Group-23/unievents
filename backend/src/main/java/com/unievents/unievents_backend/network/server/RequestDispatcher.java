package com.unievents.unievents_backend.network.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.domain.service.SessionContext;
import com.unievents.unievents_backend.domain.service.SessionManager;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;

import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe operation registry and router for incoming TCP protocol requests.
 */
public class RequestDispatcher {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RequestDispatcher.class);
    private static final Set<String> PUBLIC = Set.of("PING", "ECHO", "REGISTER", "LOGIN");
    private static final Set<String> ADMIN = Set.of("GET_METRICS", "CREATE_EVENT", "UPDATE_EVENT", "DELETE_EVENT");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, RequestHandler> handlers = new ConcurrentHashMap<>();
    private volatile SessionManager sessionManager;

    public RequestDispatcher() {
        this(null);
    }

    public RequestDispatcher(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
        registerDefaultHandlers();
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    private void registerDefaultHandlers() {
        // Default PING -> PONG handler
        registerHandler("PING", (request, context) -> {
            ObjectNode data = MAPPER.createObjectNode();
            data.put("message", "PONG");
            data.put("timestamp", System.currentTimeMillis());
            return ResponseEnvelope.success(request.requestId(), data);
        });

        // Default ECHO handler
        registerHandler("ECHO", (request, context) ->
                ResponseEnvelope.success(request.requestId(), request.payload())
        );
    }

    /**
     * Registers a request handler for a specific operation type.
     *
     * @param operationType the command name (case-insensitive)
     * @param handler       the request handler implementation
     */
    public void registerHandler(String operationType, RequestHandler handler) {
        if (operationType == null || operationType.isBlank()) {
            throw new IllegalArgumentException("operationType must not be null or blank");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler must not be null");
        }
        handlers.put(operationType.trim().toUpperCase(Locale.ROOT), handler);
    }

    /**
     * Checks if an operation type is registered.
     */
    public boolean hasHandler(String operationType) {
        if (operationType == null || operationType.isBlank()) {
            return false;
        }
        return handlers.containsKey(operationType.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * Dispatches an inbound request envelope to its registered handler.
     * Returns an error response if type is missing, unknown, or execution fails.
     *
     * @param request the request envelope
     * @param context the connection context
     * @return the response envelope
     */
    public ResponseEnvelope dispatch(RequestEnvelope request, ConnectionContext context) {
        if (request == null || request.type() == null || request.type().isBlank()) {
            String reqId = (request != null && request.requestId() != null)
                    ? request.requestId()
                    : UUID.randomUUID().toString();
            return ResponseEnvelope.error(reqId, ErrorCode.INVALID_REQUEST, "Missing or invalid request type");
        }

        RequestHandler handler = handlers.get(request.type().trim().toUpperCase(Locale.ROOT));
        if (handler == null) {
            return ResponseEnvelope.error(
                    request.requestId(),
                    ErrorCode.UNKNOWN_ACTION,
                    "Unknown request type: " + request.type()
            );
        }

        String operation = request.type().trim().toUpperCase(Locale.ROOT);
        String token = resolveToken(request);
        if (!PUBLIC.contains(operation) && sessionManager != null && sessionManager.isExpired(token)) {
            sessionManager.invalidateSession(token);
            return ResponseEnvelope.error(request.requestId(), ErrorCode.SESSION_EXPIRED, "Session has expired.");
        }
        ConnectionContext effectiveContext = enrichContext(request, context);
        if (!PUBLIC.contains(operation) && !effectiveContext.isAuthenticated()) {
            return ResponseEnvelope.error(request.requestId(), ErrorCode.UNAUTHENTICATED, "A valid session token is required.");
        }
        if (ADMIN.contains(operation) && !"ADMIN".equals(effectiveContext.role())) {
            return ResponseEnvelope.error(request.requestId(), ErrorCode.FORBIDDEN, "Administrator privilege required.");
        }

        try {
            return handler.handle(request, effectiveContext);
        } catch (Exception e) {
            log.error("Request {} failed", request.requestId(), e);
            return ResponseEnvelope.error(
                    request.requestId(),
                    ErrorCode.INTERNAL_ERROR,
                    "Internal error processing request."
            );
        }
    }

    /**
     * Enriches the connection context with authenticated user identity if the request contains
     * a valid session token and session manager is configured.
     *
     * @param request the request envelope
     * @param context the current connection context
     * @return enriched connection context (or an identity-cleared context if unauthenticated)
     */
    public ConnectionContext enrichContext(RequestEnvelope request, ConnectionContext context) {
        ConnectionContext ctx = context == null
            ? new ConnectionContext(null, null, null, null, null)
            : context.withUser(null, null, null);
        if (sessionManager != null && request != null) {
            String token = resolveToken(request);
            if (token != null && !token.isBlank()) {
                Optional<SessionContext> sessionOpt = sessionManager.validateSession(token);
                if (sessionOpt.isPresent()) {
                    SessionContext session = sessionOpt.get();
                    String roleName = session.role() != null ? session.role().name() : null;
                    if (ctx != null) {
                        ctx = ctx.withUser(session.userId(), session.email(), roleName);
                    } else {
                        ctx = new ConnectionContext(null, session.userId(), session.email(), roleName, null);
                    }
                }
            }
        }
        return ctx;
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
