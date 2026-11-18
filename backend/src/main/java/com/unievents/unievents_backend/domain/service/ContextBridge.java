package com.unievents.unievents_backend.domain.service;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Context bridge integrating Member 01's authentication sessions with downstream services
 * (such as Member 03's BookingService) to guarantee that user identities cannot be spoofed.
 */
@Component
public class ContextBridge {

    private final SessionManager sessionManager;

    public ContextBridge(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Extracts and validates the authenticated userId from the session token.
     *
     * @param token session token
     * @return verified userId
     * @throws InvalidCredentialsException if session token is missing, invalid, or expired
     */
    public Long extractAuthenticatedUserId(String token) {
        return sessionManager.getVerifiedUserId(token);
    }

    /**
     * Safely attempts to resolve the authenticated userId without throwing.
     */
    public Optional<Long> findAuthenticatedUserId(String token) {
        return sessionManager.validateSession(token).map(SessionContext::userId);
    }
}
