package com.unievents.unievents_backend.domain.service;

/**
 * Thrown when registration or authentication input violates domain validation rules.
 */
public class AuthValidationException extends RuntimeException {
    public AuthValidationException(String message) {
        super(message);
    }
}
