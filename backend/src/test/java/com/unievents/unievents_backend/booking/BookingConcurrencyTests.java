package com.unievents.unievents_backend.booking;

import com.unievents.unievents_backend.seat.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.config.import=",
    "spring.datasource.url=jdbc:h2:mem:booking-concurrency;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.hikari.maximum-pool-size=10",
    "spring.datasource.hikari.connection-timeout=10000",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:booking-users.sql,classpath:db/migration/V1__create_seats_and_bookings.sql"
})
class BookingConcurrencyTests {
    private static final int CLIENTS = 10;
    @Autowired BookingService service;
    @Autowired SeatRepository seats;
    @Autowired BookingRepository bookings;
    @Autowired JdbcTemplate db;
    List<Long> users;

    @BeforeEach
    void setup() {
        db.update("DELETE FROM bookings");
        db.update("DELETE FROM seats");
        db.update("DELETE FROM app_users");
        for (int i = 0; i < CLIENTS; i++) {
            db.update("INSERT INTO app_users(name,email,password_hash) VALUES (?,?,?)",
                "Student " + i, "student" + i + "@example.com", "test");
        }
        users = db.queryForList("SELECT id FROM app_users ORDER BY id", Long.class);
    }

    @RepeatedTest(5)
    void tenStudentsCompetingForOneSeatProduceOneWinner() throws Exception {
        var seat = seats.saveAndFlush(new Seat(10L, "A1"));
        var requests = new ArrayList<BookingRequest>();
        for (int i = 0; i < CLIENTS; i++) requests.add(new BookingRequest(10L, seat.getId()));
        var outcomes = race(requests);
        var winners = outcomes.stream().filter(outcome -> outcome.result() != null).toList();
        assertEquals(1, winners.size());
        assertEquals(9, outcomes.stream().filter(outcome -> outcome.error() == BookingException.Code.SEAT_UNAVAILABLE).count());
        assertEquals(1, bookings.count());
        var winner = winners.getFirst();
        var persisted = bookings.findById(winner.result().bookingId()).orElseThrow();
        assertEquals(winner.userId(), persisted.getUserId());
        assertEquals(seat.getId(), persisted.getSeatId());
        assertEquals(winner.result().bookingReference(), persisted.getBookingReference());
        assertEquals(Seat.Status.BOOKED, seats.findById(seat.getId()).orElseThrow().getStatus());
    }

    @RepeatedTest(5)
    void tenStudentsBookingDifferentSeatsAllSucceed() throws Exception {
        var requests = new ArrayList<BookingRequest>();
        for (int i = 0; i < CLIENTS; i++) {
            var seat = seats.saveAndFlush(new Seat(10L, "A" + i));
            requests.add(new BookingRequest(10L, seat.getId()));
        }
        var outcomes = race(requests);
        assertTrue(outcomes.stream().allMatch(outcome -> outcome.result() != null));
        assertEquals(CLIENTS, bookings.count());
        assertEquals(CLIENTS, outcomes.stream().map(outcome -> outcome.result().bookingReference()).distinct().count());
        for (int i = 0; i < CLIENTS; i++) {
            var outcome = outcomes.get(i);
            var persisted = bookings.findById(outcome.result().bookingId()).orElseThrow();
            assertEquals(users.get(i), persisted.getUserId());
            assertEquals(requests.get(i).seatId(), persisted.getSeatId());
            assertEquals(Seat.Status.BOOKED, seats.findById(persisted.getSeatId()).orElseThrow().getStatus());
        }
    }

    private List<Outcome> race(List<BookingRequest> requests) throws Exception {
        var ready = new CountDownLatch(CLIENTS);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(CLIENTS);
        var futures = new ArrayList<Future<Outcome>>();
        try {
            for (int i = 0; i < CLIENTS; i++) {
                Long userId = users.get(i);
                var request = requests.get(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS), "Start gate timed out");
                    try {
                        return new Outcome(userId, service.bookSeat(userId, request), null);
                    } catch (BookingException ex) {
                        // Only an expected conflict counts as a rejected booking.
                        assertEquals(BookingException.Code.SEAT_UNAVAILABLE, ex.getCode());
                        return new Outcome(userId, null, ex.getCode());
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers failed to reach start gate");
            start.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            var outcomes = new ArrayList<Outcome>();
            for (var future : futures) {
                outcomes.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS), "Booking workers did not terminate");
        }
    }

    private record Outcome(Long userId, BookingResult result, BookingException.Code error) {}
}
