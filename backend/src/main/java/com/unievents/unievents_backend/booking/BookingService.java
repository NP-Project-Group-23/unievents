package com.unievents.unievents_backend.booking;

import com.unievents.unievents_backend.seat.SeatRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.unievents.unievents_backend.booking.BookingException.Code.*;

@Service
@Transactional(readOnly = true)
public class BookingService {
    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final com.unievents.unievents_backend.event.EventRepository events;

    public BookingService(SeatRepository seats, BookingRepository bookings, com.unievents.unievents_backend.event.EventRepository events) {
        this.seats = seats;
        this.bookings = bookings;
        this.events = events;
    }

    /** userId must come from the authenticated session or trusted TCP context. */
    @Transactional
    public BookingResult bookSeat(Long userId, BookingRequest request) {
        requireUser(userId);
        if (request == null || !positive(request.eventId()) || !positive(request.seatId())) {
            throw new BookingException(INVALID_REQUEST, "Event and seat IDs must be positive.");
        }
        var event = events.findForBooking(request.eventId())
            .orElseThrow(() -> new BookingException(EVENT_NOT_FOUND, "Event not found."));
        if (!event.getStartsAt().isAfter(java.time.Instant.now()))
            throw new BookingException(VALIDATION_FAILED, "Booking is closed for this event.");
        seats.findByIdAndEventId(request.seatId(), request.eventId())
            .orElseThrow(() -> new BookingException(SEAT_NOT_FOUND, "Seat not found for this event."));
        if (seats.reserveIfAvailable(request.seatId(), request.eventId()) != 1) {
            throw new BookingException(SEAT_UNAVAILABLE, "Seat is already booked.");
        }
        String reference = "UE-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        // Let insertion failures propagate so Spring rolls back the reservation too.
        return BookingResult.from(bookings.saveAndFlush(
            new Booking(userId, request.eventId(), request.seatId(), reference)));
    }

    /** First page of history; use the overload to retrieve subsequent pages. */
    public Page<BookingResult> getMyBookings(Long userId) {
        return getMyBookings(userId, 0, 20);
    }

    public Page<BookingResult> getMyBookings(Long userId, int page, int size) {
        requireUser(userId);
        if (page < 0 || size < 1 || size > 100) {
            throw new BookingException(INVALID_REQUEST, "Page must be nonnegative and size must be between 1 and 100.");
        }
        return bookings.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page, size))
            .map(BookingResult::from);
    }

    public BookingResult getMyBooking(Long userId, Long bookingId) {
        requireUser(userId);
        if (!positive(bookingId)) {
            throw new BookingException(INVALID_REQUEST, "Booking ID must be positive.");
        }
        return bookings.findByIdAndUserId(bookingId, userId).map(BookingResult::from)
            .orElseThrow(() -> new BookingException(BOOKING_NOT_FOUND, "Booking not found."));
    }

    private static boolean positive(Long id) { return id != null && id > 0; }

    private static void requireUser(Long userId) {
        if (!positive(userId)) {
            throw new BookingException(UNAUTHENTICATED, "An authenticated user is required.");
        }
    }
}
