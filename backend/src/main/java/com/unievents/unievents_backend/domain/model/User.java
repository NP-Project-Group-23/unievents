package com.unievents.unievents_backend.domain.model;

import java.time.Instant;

/**
 * Domain model representing an authenticated user account.
 *
 * @param id           Primary database identifier (null prior to persistence)
 * @param name         User's full display name
 * @param email        Normalized, unique email address
 * @param passwordHash BCrypt password hash
 * @param role         Assigned role (STUDENT or ADMIN)
 * @param createdAt    Account creation timestamp
 */
public record User(
        Long id,
        String name,
        String email,
        String passwordHash,
        Role role,
        Instant createdAt
) {
    public User {
        if (role == null) {
            role = Role.STUDENT;
        }
    }

    public static User create(String name, String email, String passwordHash, Role role) {
        return new User(null, name, email, passwordHash, role != null ? role : Role.STUDENT, Instant.now());
    }

    public User withId(Long id) {
        return new User(id, this.name, this.email, this.passwordHash, this.role, this.createdAt);
    }
}
