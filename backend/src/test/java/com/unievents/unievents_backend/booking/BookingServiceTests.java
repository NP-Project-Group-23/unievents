package com.unievents.unievents_backend.booking;

import com.unievents.unievents_backend.seat.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import static com.unievents.unievents_backend.booking.BookingException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.config.import=",
    "spring.datasource.url=jdbc:h2:mem:booking-service;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:booking-users.sql,classpath:db/migration/V1__create_seats_and_bookings.sql"
})
class BookingServiceTests {
    @Autowired BookingService service;
    @Autowired SeatRepository seats;
    @Autowired BookingRepository bookings;
    @Autowired JdbcTemplate db;
    Long userId;
    Long otherUserId;
    Seat seat;

    @BeforeEach
    void setup() {
        db.update("DELETE FROM bookings");
        db.update("DELETE FROM seats");
        db.update("DELETE FROM app_users");
        db.update("INSERT INTO app_users(name,email,password_hash) VALUES ('One','one@example.com','test'),('Two','two@example.com','test')");
        userId = db.queryForObject("SELECT id FROM app_users WHERE email='one@example.com'", Long.class);
        otherUserId = db.queryForObject("SELECT id FROM app_users WHERE email='two@example.com'", Long.class);
        seat = seats.saveAndFlush(new Seat(10L, "A1"));
    }

    @Test
    void successfulBookingReturnsPersistedConfirmation() {
        var result = service.bookSeat(userId, new BookingRequest(10L, seat.getId()));
        assertNotNull(result.bookingId());
        assertTrue(result.bookingReference().matches("UE-[0-9A-F-]{36}"));
        assertEquals(10L, result.eventId());
        assertEquals(seat.getId(), result.seatId());
        assertEquals(Booking.Status.CONFIRMED, result.status());
        assertNotNull(result.createdAt());
        assertEquals(result.bookingReference(), service.getMyBooking(userId, result.bookingId()).bookingReference());
        assertEquals(Seat.Status.BOOKED, seats.findById(seat.getId()).orElseThrow().getStatus());
    }

    @Test
    void rejectsAlreadyBookedSeat() {
        service.bookSeat(userId, new BookingRequest(10L, seat.getId()));
        assertCode(SEAT_UNAVAILABLE, () -> service.bookSeat(otherUserId, new BookingRequest(10L, seat.getId())));
        assertEquals(1, bookings.count());
    }

    @Test
    void rejectsMissingSeatAndWrongEvent() {
        assertCode(SEAT_NOT_FOUND, () -> service.bookSeat(userId, new BookingRequest(11L, seat.getId())));
        assertCode(SEAT_NOT_FOUND, () -> service.bookSeat(userId, new BookingRequest(10L, Long.MAX_VALUE)));
        assertEquals(Seat.Status.AVAILABLE, seats.findById(seat.getId()).orElseThrow().getStatus());
        assertEquals(0, bookings.count());
    }

    @Test
    void historyIsPaginatedAndPrivate() {
        var first = service.bookSeat(userId, new BookingRequest(10L, seat.getId()));
        var secondSeat = seats.saveAndFlush(new Seat(10L, "A2"));
        var second = service.bookSeat(userId, new BookingRequest(10L, secondSeat.getId()));
        var thirdSeat = seats.saveAndFlush(new Seat(10L, "A3"));
        service.bookSeat(otherUserId, new BookingRequest(10L, thirdSeat.getId()));
        assertEquals(2, service.getMyBookings(userId).getTotalElements());
        assertEquals(second.bookingId(), service.getMyBookings(userId, 0, 1).getContent().getFirst().bookingId());
        assertEquals(first.bookingId(), service.getMyBookings(userId, 1, 1).getContent().getFirst().bookingId());
        assertEquals(1, service.getMyBookings(otherUserId).getTotalElements());
        assertCode(BOOKING_NOT_FOUND, () -> service.getMyBooking(otherUserId, first.bookingId()));
        assertCode(BOOKING_NOT_FOUND, () -> service.getMyBooking(userId, Long.MAX_VALUE));
    }

    @Test
    void validatesDirectServiceCalls() {
        assertCode(UNAUTHENTICATED, () -> service.bookSeat(null, new BookingRequest(10L, seat.getId())));
        assertCode(UNAUTHENTICATED, () -> service.getMyBookings(0L));
        assertCode(UNAUTHENTICATED, () -> service.getMyBooking(-1L, 1L));
        assertCode(INVALID_REQUEST, () -> service.bookSeat(userId, null));
        assertCode(INVALID_REQUEST, () -> service.bookSeat(userId, new BookingRequest(null, seat.getId())));
        assertCode(INVALID_REQUEST, () -> service.bookSeat(userId, new BookingRequest(10L, 0L)));
        assertCode(INVALID_REQUEST, () -> service.getMyBooking(userId, null));
        assertCode(INVALID_REQUEST, () -> service.getMyBookings(userId, -1, 20));
        assertCode(INVALID_REQUEST, () -> service.getMyBookings(userId, 0, 101));
        assertEquals(0, bookings.count());
    }

    @Test
    void insertionFailureRollsBackReservation() {
        assertThrows(DataIntegrityViolationException.class,
            () -> service.bookSeat(Long.MAX_VALUE, new BookingRequest(10L, seat.getId())));
        assertEquals(Seat.Status.AVAILABLE, seats.findById(seat.getId()).orElseThrow().getStatus());
        assertEquals(0, bookings.count());
        assertNotNull(service.bookSeat(userId, new BookingRequest(10L, seat.getId())).bookingId());
    }

    @Test
    void concurrentServiceCallsCommitExactlyOneBooking() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> attempt = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    service.bookSeat(userId, new BookingRequest(10L, seat.getId()));
                    return true;
                } catch (BookingException ex) {
                    assertEquals(SEAT_UNAVAILABLE, ex.getCode());
                    return false;
                }
            };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, bookings.count());
        assertEquals(Seat.Status.BOOKED, seats.findById(seat.getId()).orElseThrow().getStatus());
    }

    private static void assertCode(BookingException.Code code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BookingException.class, action).getCode());
    }
}
