package com.unievents.unievents_backend.domain.service;

import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.model.User;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory session manager with sliding window idle eviction.
 * Default idle expiration is 30 minutes.
 */
@Service
public class SessionManager {

    public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final Duration idleTimeout;

    public SessionManager() {
        this(DEFAULT_IDLE_TIMEOUT);
    }

    public SessionManager(Duration idleTimeout) {
        this.idleTimeout = idleTimeout != null ? idleTimeout : DEFAULT_IDLE_TIMEOUT;
    }

    /**
     * Creates and registers a new active session for the authenticated user.
     *
     * @param user authenticated user entity
     * @return generated session context
     */
    public SessionContext createSession(User user) {
        if (user == null || user.id() == null) {
            throw new IllegalArgumentException("Cannot create session for null or unpersisted user");
        }
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        SessionContext context = new SessionContext(
                token,
                user.id(),
                user.email(),
                user.role() != null ? user.role() : Role.STUDENT,
                now,
                now
        );
        sessions.put(token, context);
        return context;
    }

    /**
     * Validates session presence and idle liveness. If active, updates the access timestamp.
     * If expired, invalidates and purges the session.
     *
     * @param token session token
     * @return optional containing active session context, or empty if expired/unknown
     */
    public Optional<SessionContext> validateSession(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        SessionContext updated = sessions.computeIfPresent(token, (key, current) -> {
            Instant now = Instant.now();
            if (now.isAfter(current.lastAccessedAt().plus(idleTimeout))) {
                // computeIfPresent removes the mapping when returning null
                return null;
            }
            return current.withLastAccessedAt(now);
        });

        return Optional.ofNullable(updated);
    }

    /**
     * Checks if a token exists and whether it is currently expired without modifying lastAccessedAt.
     */
    public boolean isExpired(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        SessionContext current = sessions.get(token);
        if (current == null) {
            return false;
        }
        return Instant.now().isAfter(current.lastAccessedAt().plus(idleTimeout));
    }

    /**
     * Explicitly invalidates and terminates a session.
     *
     * @param token session token
     * @return true if the session was active and removed, false if not found
     */
    public boolean invalidateSession(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return sessions.remove(token) != null;
    }

    /**
     * Verifies that the session is active and possesses the required role privilege.
     * ADMIN satisfies all role requirements; STUDENT satisfies STUDENT requirement.
     *
     * @param token        session token
     * @param requiredRole required minimum role
     * @return true if session is valid and authorized
     */
    public boolean requireRole(String token, Role requiredRole) {
        Optional<SessionContext> ctx = validateSession(token);
        if (ctx.isEmpty()) {
            return false;
        }
        Role userRole = ctx.get().role();
        if (requiredRole == null || requiredRole == Role.STUDENT) {
            return true;
        }
        return userRole == Role.ADMIN;
    }

    /**
     * Resolves the verified userId from an active session token.
     *
     * @param token session token
     * @return authenticated user primary key
     * @throws InvalidCredentialsException if token is missing, invalid, or expired
     */
    public Long getVerifiedUserId(String token) {
        return validateSession(token)
                .map(SessionContext::userId)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid or expired session token"));
    }

    /**
     * Returns the total count of currently held session tokens.
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * Clears all sessions.
     */
    public void clear() {
        sessions.clear();
    }
}
