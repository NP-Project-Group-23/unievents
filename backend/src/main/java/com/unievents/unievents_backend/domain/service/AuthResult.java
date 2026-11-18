package com.unievents.unievents_backend.domain.service;

import com.unievents.unievents_backend.domain.model.User;

/**
 * Result returned upon successful user authentication containing the user and session context.
 *
 * @param user    authenticated user entity
 * @param session active session context
 */
public record AuthResult(
        User user,
        SessionContext session
) {
    public String token() {
        return session != null ? session.token() : null;
    }
}
