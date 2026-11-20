package com.unievents.unievents_backend.booking;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record BookingRequest(@NotNull @Positive Long eventId, @NotNull @Positive Long seatId, String idempotencyKey) {
    public BookingRequest(Long eventId, Long seatId) {
        this(eventId, seatId, null);
    }
}
