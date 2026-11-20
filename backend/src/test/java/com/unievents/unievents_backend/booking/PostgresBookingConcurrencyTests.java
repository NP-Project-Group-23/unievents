package com.unievents.unievents_backend.booking;

import com.unievents.unievents_backend.seat.Seat;
import com.unievents.unievents_backend.seat.SeatRepository;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in evidence against real PostgreSQL. The suite owns one random schema and
 * never deletes or updates rows outside it.
 */
@EnabledIfSystemProperty(named = "booking.postgres.test", matches = "true")
@SpringBootTest(properties = {
    "spring.config.import=", "spring.flyway.enabled=true", "spring.sql.init.mode=never",
    "spring.jpa.hibernate.ddl-auto=validate", "spring.datasource.hikari.maximum-pool-size=15",
    "spring.datasource.hikari.connection-timeout=15000", "unievents.tcp.enabled=false"
})
class PostgresBookingConcurrencyTests {
    private static final int CLIENTS = 10;
    private static final String SCHEMA = "m3_booking_" + UUID.randomUUID().toString().replace("-", "");
    private static final String URL = System.getenv("BOOKING_TEST_DB_URL");
    private static final String USERNAME = System.getenv("BOOKING_TEST_DB_USERNAME");
    private static final String PASSWORD = System.getenv("BOOKING_TEST_DB_PASSWORD");

    @Autowired BookingService service;
    @Autowired SeatRepository seats;
    @Autowired BookingRepository bookings;
    @Autowired JdbcTemplate db;
    private List<Long> users;
    private Long eventId;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry properties) throws Exception {
        if (URL == null || URL.isBlank()) {
            throw new IllegalStateException("BOOKING_TEST_DB_URL is required when -Dbooking.postgres.test=true.");
        }
        if (!URL.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("BOOKING_TEST_DB_URL must be a JDBC PostgreSQL URL.");
        }
        if (URL.toLowerCase().contains("currentschema=")) {
            throw new IllegalStateException("BOOKING_TEST_DB_URL must not define currentSchema.");
        }
        try (var connection = DriverManager.getConnection(URL, credentials());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + SCHEMA + "\"");
        }
        properties.add("spring.datasource.url", () -> URL);
        if (USERNAME != null && !USERNAME.isBlank()) properties.add("spring.datasource.username", () -> USERNAME);
        if (PASSWORD != null) properties.add("spring.datasource.password", () -> PASSWORD);
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void fixtures() {
        String marker = UUID.randomUUID().toString();
        users = new ArrayList<>();
        for (int i = 0; i < CLIENTS; i++) {
            String email = "m3-" + marker + "-" + i + "@test.invalid";
            db.update("INSERT INTO app_users(name,email,password_hash) VALUES (?,?,?)", "M3 Client " + i, email, "test");
            users.add(db.queryForObject("SELECT id FROM app_users WHERE email=?", Long.class, email));
        }
        String title = "M3 PostgreSQL " + marker;
        db.update("INSERT INTO events(title,description,venue,starts_at,capacity,created_by) VALUES (?,?,?,?,?,?)",
            title, "Isolated concurrency evidence", "Test", OffsetDateTime.now().plusDays(1), 20, users.getFirst());
        eventId = db.queryForObject("SELECT id FROM events WHERE title=?", Long.class, title);
    }

    @Test
    void realPostgresAllowsOneWinnerForOneSeat() throws Exception {
        var seat = seats.saveAndFlush(new Seat(eventId, "A1"));
        var requests = new ArrayList<BookingRequest>();
        for (int i = 0; i < CLIENTS; i++) requests.add(new BookingRequest(eventId, seat.getId(), "same-seat-" + i));

        var outcomes = race(requests);
        assertEquals(1, outcomes.stream().filter(outcome -> outcome.result != null).count());
        assertEquals(9, outcomes.stream().filter(outcome -> outcome.error == BookingException.Code.SEAT_UNAVAILABLE).count());
        assertEquals(1, bookings.findByEventIdOrderByCreatedAtDescIdDesc(eventId,
            org.springframework.data.domain.PageRequest.of(0, 20)).getTotalElements());
        assertEquals(Seat.Status.BOOKED, seats.findById(seat.getId()).orElseThrow().getStatus());
    }

    @Test
    void realPostgresAllowsIndependentSeatsInParallel() throws Exception {
        var requests = new ArrayList<BookingRequest>();
        for (int i = 0; i < CLIENTS; i++) {
            var seat = seats.saveAndFlush(new Seat(eventId, "B" + (i + 1)));
            requests.add(new BookingRequest(eventId, seat.getId(), "different-seat-" + i));
        }
        var outcomes = race(requests);
        assertTrue(outcomes.stream().allMatch(outcome -> outcome.result != null));
        assertEquals(CLIENTS, outcomes.stream().map(outcome -> outcome.result.bookingReference()).distinct().count());
    }

    private List<Outcome> race(List<BookingRequest> requests) throws Exception {
        var ready = new CountDownLatch(CLIENTS);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(CLIENTS)) {
            var futures = new ArrayList<Future<Outcome>>();
            for (int i = 0; i < CLIENTS; i++) {
                Long userId = users.get(i);
                BookingRequest request = requests.get(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(15, TimeUnit.SECONDS));
                    try {
                        return new Outcome(service.bookSeat(userId, request), null);
                    } catch (BookingException exception) {
                        return new Outcome(null, exception.getCode());
                    }
                }));
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            start.countDown();
            var outcomes = new ArrayList<Outcome>();
            for (var future : futures) outcomes.add(future.get(45, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            start.countDown();
        }
    }

    @AfterAll
    static void removeOwnedSchema() throws Exception {
        if (!SCHEMA.matches("m3_booking_[a-f0-9]{32}")) {
            throw new IllegalStateException("Refusing to drop unexpected schema name: " + SCHEMA);
        }
        try (var connection = DriverManager.getConnection(URL, credentials());
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
        }
    }

    private static Properties credentials() {
        var properties = new Properties();
        if (USERNAME != null && !USERNAME.isBlank()) properties.setProperty("user", USERNAME);
        if (PASSWORD != null) properties.setProperty("password", PASSWORD);
        return properties;
    }

    private record Outcome(BookingResult result, BookingException.Code error) {}
}
