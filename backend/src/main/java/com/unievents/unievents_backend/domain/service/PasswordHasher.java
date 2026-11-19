package com.unievents.unievents_backend.domain.service;

/**
 * Contract for secure hashing and verification of passwords.
 */
public interface PasswordHasher {

    /**
     * Produces a secure one-way salted hash of the provided plaintext password.
     *
     * @param rawPassword plaintext password
     * @return salted hash string
     */
    String hash(String rawPassword);

    /**
     * Verifies whether a plaintext password matches an existing salted hash.
     *
     * @param rawPassword    plaintext password
     * @param hashedPassword salted hash string
     * @return true if the password matches, false otherwise
     */
    boolean verify(String rawPassword, String hashedPassword);
}
