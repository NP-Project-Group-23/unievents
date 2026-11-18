package com.unievents.unievents_backend.domain.service;

import com.unievents.unievents_backend.domain.model.Role;

import java.time.Instant;

/**
 * Immutable session context associated with an authenticated client session token.
 *
 * @param token          Cryptographically secure random or UUID session token
 * @param userId         Authenticated user primary key
 * @param email          Authenticated user email
 * @param role           Assigned role
 * @param createdAt      Session generation timestamp
 * @param lastAccessedAt Last time this session performed an authenticated operation
 */
public record SessionContext(
        String token,
        Long userId,
        String email,
        Role role,
        Instant createdAt,
        Instant lastAccessedAt
) {
    public SessionContext withLastAccessedAt(Instant newLastAccessedAt) {
        return new SessionContext(this.token, this.userId, this.email, this.role, this.createdAt, newLastAccessedAt);
    }
}
