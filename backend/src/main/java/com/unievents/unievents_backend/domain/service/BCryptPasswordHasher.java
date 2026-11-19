package com.unievents.unievents_backend.domain.service;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * OpenBSD BCrypt password hasher implementation.
 * Enforces a strict 72-byte UTF-8 pre-hash guard and minimum 8 characters.
 */
@Service
public class BCryptPasswordHasher implements PasswordHasher {

    public static final int DEFAULT_STRENGTH = 12;
    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private final BCryptPasswordEncoder encoder;

    public BCryptPasswordHasher() {
        this(DEFAULT_STRENGTH);
    }

    public BCryptPasswordHasher(int strength) {
        this.encoder = new BCryptPasswordEncoder(strength);
    }

    @Override
    public String hash(String rawPassword) {
        validatePassword(rawPassword);
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean verify(String rawPassword, String hashedPassword) {
        if (rawPassword == null || hashedPassword == null || hashedPassword.isBlank()) {
            return false;
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return false;
        }
        try {
            return encoder.matches(rawPassword, hashedPassword);
        } catch (Exception e) {
            return false;
        }
    }

    private void validatePassword(String rawPassword) {
        if (rawPassword == null) {
            throw new AuthValidationException("Password must not be null.");
        }
        if (rawPassword.length() < MIN_LENGTH) {
            throw new AuthValidationException("Password must be at least " + MIN_LENGTH + " characters long.");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new AuthValidationException("Password must not exceed " + MAX_BYTES + " UTF-8 bytes.");
        }
    }
}
