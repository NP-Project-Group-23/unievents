package com.unievents.unievents_backend;

import com.unievents.unievents_backend.booking.*;
import com.unievents.unievents_backend.seat.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.config.import=",
    "spring.datasource.url=jdbc:h2:mem:booking;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.sql.init.mode=never", "spring.flyway.enabled=true",
    "spring.sql.init.schema-locations=classpath:booking-users.sql,classpath:db/migration/V1__create_seats_and_bookings.sql"
})
class BookingPersistenceTests {
    @Autowired SeatRepository seats;
    @Autowired BookingRepository bookings;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager manager;
    TransactionTemplate tx;
    Long userId;

    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(manager);
        db.update("DELETE FROM bookings");
        db.update("DELETE FROM seats");
        db.update("DELETE FROM events");
        db.update("DELETE FROM app_users");
        db.update("INSERT INTO app_users(name,email,password_hash) VALUES ('Student','student@example.com','test')");
        userId = db.queryForObject("SELECT id FROM app_users", Long.class);
        db.update("INSERT INTO events(id,title,description,venue,starts_at,capacity,created_by) OVERRIDING SYSTEM VALUE VALUES (10,'Event 10','','Hall',?,100,?),(11,'Event 11','','Hall',?,100,?)",
            java.time.OffsetDateTime.now().plusDays(5), userId, java.time.OffsetDateTime.now().plusDays(6), userId);
    }

    @Test
    void persistsReservationAndScopesHistoryToUser() {
        var seat = seats.saveAndFlush(new Seat(10L, "A1"));
        Long id = tx.execute(status -> {
            assertEquals(0, seats.reserveIfAvailable(seat.getId(), 11L));
            assertEquals(1, seats.reserveIfAvailable(seat.getId(), 10L));
            assertEquals(0, seats.reserveIfAvailable(seat.getId(), 10L));
            return bookings.saveAndFlush(new Booking(userId, 10L, seat.getId(), "REF-1")).getId();
        });
        assertEquals(Seat.Status.BOOKED, seats.findById(seat.getId()).orElseThrow().getStatus());
        var booking = bookings.findByIdAndUserId(id, userId).orElseThrow();
        assertNotNull(booking.getCreatedAt());
        assertEquals(Booking.Status.CONFIRMED, booking.getStatus());
        assertTrue(bookings.findByIdAndUserId(id, userId + 1).isEmpty());
        assertTrue(bookings.findByBookingReferenceAndUserId("REF-1", userId).isPresent());
        assertEquals(1, bookings.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, bookings.findByEventIdOrderByCreatedAtDescIdDesc(10L, PageRequest.of(0, 10)).getTotalElements());
        assertEquals(1, seats.findByEventIdOrderBySeatNumberAsc(10L).size());
        assertTrue(seats.findByEventIdAndStatusOrderBySeatNumberAsc(10L, Seat.Status.AVAILABLE).isEmpty());
    }

    @Test
    void constraintsRejectDuplicateSeatsReferencesAndInvalidRelationships() {
        var seat = seats.saveAndFlush(new Seat(10L, "A1"));
        var other = seats.saveAndFlush(new Seat(11L, "A1"));
        assertThrows(DataIntegrityViolationException.class, () -> seats.saveAndFlush(new Seat(10L, "A1")));
        assertThrows(DataIntegrityViolationException.class, () -> bookings.saveAndFlush(new Booking(userId, 11L, seat.getId(), "WRONG-EVENT")));
        assertThrows(DataIntegrityViolationException.class, () -> bookings.saveAndFlush(new Booking(-1L, 10L, seat.getId(), "WRONG-USER")));
        bookings.saveAndFlush(new Booking(userId, 10L, seat.getId(), "REF-1"));
        assertThrows(DataIntegrityViolationException.class, () -> bookings.saveAndFlush(new Booking(userId, 10L, seat.getId(), "REF-2")));
        assertThrows(DataIntegrityViolationException.class, () -> bookings.saveAndFlush(new Booking(userId, 11L, other.getId(), "REF-1")));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE seats SET status='INVALID' WHERE id=?", seat.getId()));
        assertThrows(DataIntegrityViolationException.class, () -> db.update("UPDATE bookings SET status='CANCELLED'"));
    }

    @Test
    void failedBookingRollsBackSeatReservation() {
        var seat = seats.saveAndFlush(new Seat(10L, "A1"));
        assertThrows(DataIntegrityViolationException.class, () -> tx.execute(status -> {
            assertEquals(1, seats.reserveIfAvailable(seat.getId(), 10L));
            return bookings.saveAndFlush(new Booking(-1L, 10L, seat.getId(), "INVALID"));
        }));
        assertEquals(Seat.Status.AVAILABLE, seats.findById(seat.getId()).orElseThrow().getStatus());
        assertEquals(0, bookings.count());
    }

    @Test
    void competingTransactionsProduceOneBooking() throws Exception {
        var seat = seats.saveAndFlush(new Seat(10L, "A1"));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> reserve = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return tx.execute(status -> {
                    int updated = seats.reserveIfAvailable(seat.getId(), 10L);
                    if (updated == 1) bookings.saveAndFlush(new Booking(userId, 10L, seat.getId(), "WINNER"));
                    return updated;
                });
            };
            var first = pool.submit(reserve);
            var second = pool.submit(reserve);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, bookings.count());
        assertEquals(Seat.Status.BOOKED, seats.findById(seat.getId()).orElseThrow().getStatus());
    }
}
