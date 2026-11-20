package com.unievents.unievents_backend.booking;

import com.unievents.unievents_backend.seat.SeatRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static com.unievents.unievents_backend.booking.BookingException.Code.*;

@Service
@Transactional(readOnly = true)
public class BookingService {
    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final com.unievents.unievents_backend.event.EventRepository events;
    private final ApplicationEventPublisher publisher;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BookingService.class);

    public BookingService(SeatRepository seats, BookingRepository bookings,
                          com.unievents.unievents_backend.event.EventRepository events,
                          ApplicationEventPublisher publisher) {
        this.seats = seats;
        this.bookings = bookings;
        this.events = events;
        this.publisher = publisher;
    }

    /** userId must come from the authenticated session or trusted TCP context. */
    @Transactional
    public BookingResult bookSeat(Long userId, BookingRequest request) {
        requireUser(userId);
        if (request == null || !positive(request.eventId()) || !positive(request.seatId())) {
            throw new BookingException(INVALID_REQUEST, "Event and seat IDs must be positive.");
        }
        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        BookingResult replay = findReplay(userId, request, idempotencyKey);
        if (replay != null) return replay;
        var event = events.findForBooking(request.eventId())
            .orElseThrow(() -> new BookingException(EVENT_NOT_FOUND, "Event not found."));
        // A concurrent request may have committed while this transaction waited for the event lock.
        replay = findReplay(userId, request, idempotencyKey);
        if (replay != null) return replay;
        if (!event.getStartsAt().isAfter(java.time.Instant.now()))
            throw new BookingException(VALIDATION_FAILED, "Booking is closed for this event.");
        seats.findByIdAndEventId(request.seatId(), request.eventId())
            .orElseThrow(() -> new BookingException(SEAT_NOT_FOUND, "Seat not found for this event."));
        if (seats.reserveIfAvailable(request.seatId(), request.eventId()) != 1) {
            throw new BookingException(SEAT_UNAVAILABLE, "Seat is already booked.");
        }
        String reference = "UE-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        // Let insertion failures propagate so Spring rolls back the reservation too.
        BookingResult result = BookingResult.from(bookings.saveAndFlush(
            new Booking(userId, request.eventId(), request.seatId(), reference, idempotencyKey)));
        registerCommittedEvent(result);
        return result;
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

    /** Administrative booking manifest for one existing event. Authorization is enforced by the protocol boundary. */
    public Page<EventBookingResult> getEventBookings(Long eventId, int page, int size) {
        if (!positive(eventId)) {
            throw new BookingException(INVALID_REQUEST, "Event ID must be positive.");
        }
        if (page < 0 || size < 1 || size > 100) {
            throw new BookingException(INVALID_REQUEST, "Page must be nonnegative and size must be between 1 and 100.");
        }
        if (!events.existsById(eventId)) {
            throw new BookingException(EVENT_NOT_FOUND, "Event not found.");
        }
        return bookings.findByEventIdOrderByCreatedAtDescIdDesc(eventId, PageRequest.of(page, size))
            .map(EventBookingResult::from);
    }

    private static boolean positive(Long id) { return id != null && id > 0; }

    private static String normalizeIdempotencyKey(String value) {
        if (value == null) return null;
        String key = value.strip();
        if (key.isEmpty() || key.length() > 64 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new BookingException(INVALID_REQUEST,
                "Idempotency key must be 1-64 URL-safe characters.");
        }
        return key;
    }

    private BookingResult findReplay(Long userId, BookingRequest request, String key) {
        if (key == null) return null;
        return bookings.findByUserIdAndIdempotencyKey(userId, key).map(existing -> {
            if (!existing.getEventId().equals(request.eventId()) || !existing.getSeatId().equals(request.seatId())) {
                throw new BookingException(INVALID_REQUEST,
                    "Idempotency key was already used for a different booking request.");
            }
            return BookingResult.from(existing);
        }).orElse(null);
    }

    private static void requireUser(Long userId) {
        if (!positive(userId)) {
            throw new BookingException(UNAUTHENTICATED, "An authenticated user is required.");
        }
    }

    private void registerCommittedEvent(BookingResult result) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Booking commit event requires an active transaction.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try {
                    publisher.publishEvent(new BookingCommitted(result.bookingId(), result.eventId(),
                        result.seatId(), java.time.Instant.now()));
                } catch (RuntimeException ex) {
                    // Notification integration must never change an already committed booking result.
                    log.error("Booking {} committed but notification publication failed", result.bookingId(), ex);
                }
            }
        });
    }
}
