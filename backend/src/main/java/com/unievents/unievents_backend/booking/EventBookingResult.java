package com.unievents.unievents_backend.booking;

import java.time.Instant;

/** Admin-only event manifest row. */
public record EventBookingResult(
    Long bookingId,
    Long userId,
    String bookingReference,
    Long eventId,
    Long seatId,
    Booking.Status status,
    Instant createdAt
) {
    static EventBookingResult from(Booking booking) {
        return new EventBookingResult(booking.getId(), booking.getUserId(), booking.getBookingReference(),
            booking.getEventId(), booking.getSeatId(), booking.getStatus(), booking.getCreatedAt());
    }
}
