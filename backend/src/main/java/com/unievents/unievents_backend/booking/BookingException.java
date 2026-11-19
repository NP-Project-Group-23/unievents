package com.unievents.unievents_backend.booking;

public class BookingException extends RuntimeException {
    public enum Code { INVALID_REQUEST, UNAUTHENTICATED, EVENT_NOT_FOUND, VALIDATION_FAILED, SEAT_NOT_FOUND, SEAT_UNAVAILABLE, BOOKING_NOT_FOUND }

    private final Code code;

    public BookingException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() { return code; }
}
