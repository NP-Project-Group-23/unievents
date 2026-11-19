package com.unievents.unievents_backend.domain.model;

/**
 * User roles within the UniTicket system.
 */
public enum Role {
    STUDENT,
    ADMIN;

    /**
     * Safely parses a role string into a {@link Role} enum.
     * Defaults to {@link Role#STUDENT} if null, blank, or unrecognized.
     *
     * @param value raw role string
     * @return the resolved Role enum (default STUDENT)
     */
    public static Role fromString(String value) {
        if (value == null || value.isBlank()) {
            return STUDENT;
        }
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return STUDENT;
        }
    }
}
