package com.unievents.unievents_backend.network.protocol;

/**
 * Standardized machine-readable error codes across UniEvents networking,
 * authentication, booking, and downstream subsystems.
 */
public enum ErrorCode {
    // Protocol & Framing
    FRAME_TOO_LARGE,
    MALFORMED_JSON,
    INVALID_REQUEST,
    UNKNOWN_ACTION,
    SERVER_OVERLOAD,
    INTERNAL_ERROR,

    // Authentication & Authorization (Member 01 Phase 2)
    VALIDATION_FAILED,
    DUPLICATE_EMAIL,
    INVALID_CREDENTIALS,
    UNAUTHENTICATED,
    SESSION_EXPIRED,
    FORBIDDEN,

    // Downstream Domain (Member 02 / Member 03)
    EVENT_NOT_FOUND,
    SEAT_NOT_FOUND,
    SEAT_ALREADY_BOOKED,
    BOOKING_NOT_FOUND
}
