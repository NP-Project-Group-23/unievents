package com.unievents.unievents_backend.booking;

import java.time.Instant;

/** Contains no student identity; safe handoff to the UDP notification boundary. */
public record BookingCommitted(Long bookingId, Long eventId, Long seatId, Instant committedAt) {}
