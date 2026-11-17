package com.unievents.unievents_backend.booking;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record BookingRequest(@NotNull @Positive Long eventId, @NotNull @Positive Long seatId) {}
