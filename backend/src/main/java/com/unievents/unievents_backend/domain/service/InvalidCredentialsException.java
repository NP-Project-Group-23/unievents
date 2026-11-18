package com.unievents.unievents_backend.domain.service;

/**
 * Thrown when user authentication fails due to incorrect email or password.
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
