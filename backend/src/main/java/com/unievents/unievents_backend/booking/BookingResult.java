package com.unievents.unievents_backend.booking;

import java.time.Instant;

public record BookingResult(Long bookingId, String bookingReference, Long eventId,
                            Long seatId, Booking.Status status, Instant createdAt) {
    static BookingResult from(Booking booking) {
        return new BookingResult(booking.getId(), booking.getBookingReference(),
            booking.getEventId(), booking.getSeatId(), booking.getStatus(), booking.getCreatedAt());
    }
}
