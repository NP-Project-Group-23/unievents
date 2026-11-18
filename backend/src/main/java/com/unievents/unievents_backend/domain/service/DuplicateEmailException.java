package com.unievents.unievents_backend.domain.service;

/**
 * Thrown when attempting to register an email address that already exists.
 */
public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException(String message) {
        super(message);
    }
}
