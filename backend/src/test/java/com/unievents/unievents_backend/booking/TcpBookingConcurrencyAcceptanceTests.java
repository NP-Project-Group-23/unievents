package com.unievents.unievents_backend.booking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.model.User;
import com.unievents.unievents_backend.domain.service.SessionManager;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.server.TcpServer;
import com.unievents.unievents_backend.seat.Seat;
import com.unievents.unievents_backend.seat.SeatRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:tcp-booking-acceptance;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
    "spring.flyway.enabled=true", "spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=validate",
    "spring.datasource.hikari.maximum-pool-size=15", "unievents.executor.max-pool-size=20"
})
class TcpBookingConcurrencyAcceptanceTests {
    @Autowired TcpServer server;
    @Autowired SessionManager sessions;
    @Autowired SeatRepository seats;
    @Autowired BookingRepository bookings;
    @Autowired JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();
    private final ArrayList<String> tokens = new ArrayList<>();
    private String adminToken;
    private Seat seat;

    @BeforeEach
    void setup() {
        sessions.clear();
        db.update("DELETE FROM bookings"); db.update("DELETE FROM seats");
        db.update("DELETE FROM events"); db.update("DELETE FROM app_users");
        db.update("INSERT INTO app_users(name,email,password_hash,role) VALUES ('Demo Admin','demo-admin@test.local','hash','ADMIN')");
        Long adminId = db.queryForObject("SELECT id FROM app_users WHERE role='ADMIN'", Long.class);
        adminToken = sessions.createSession(new User(adminId, "Demo Admin", "demo-admin@test.local",
            "hash", Role.ADMIN, Instant.now())).token();
        for (int i = 0; i < 10; i++) {
            db.update("INSERT INTO app_users(name,email,password_hash,role) VALUES (?,?,?,'STUDENT')",
                "Client " + i, "tcp-client-" + i + "@test.local", "hash");
        }
        var users = db.queryForList("SELECT id FROM app_users WHERE role='STUDENT' ORDER BY id", Long.class);
        db.update("INSERT INTO events(id,title,description,venue,starts_at,capacity,created_by) "
            + "OVERRIDING SYSTEM VALUE VALUES (30,'TCP Race','','Hall',?,1,?)", OffsetDateTime.now().plusDays(1), adminId);
        seat = seats.saveAndFlush(new Seat(30L, "A1"));
        for (int i = 0; i < users.size(); i++) tokens.add(sessions.createSession(
            new User(users.get(i), "Client " + i, "tcp-client-" + i + "@test.local", "hash", Role.STUDENT, Instant.now())).token());
    }

    @Test
    void tenRealTcpClientsProduceOneWinnerAndNineConflicts() throws Exception {
        var ready = new CountDownLatch(10);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(10)) {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (String token : tokens) futures.add(pool.submit(() -> {
                try (var client = new TcpClient("127.0.0.1", server.getPort(), Duration.ofSeconds(15), false)) {
                    client.connect();
                    ready.countDown();
                    assertTrue(start.await(15, TimeUnit.SECONDS));
                    var payload = json.createObjectNode().put("eventId", 30).put("seatId", seat.getId());
                    var response = client.sendSync(RequestEnvelope.of("BOOK_SEAT", token, payload));
                    return response.isSuccess() ? "SUCCESS" : response.errorCode();
                }
            }));
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            start.countDown();
            var results = new ArrayList<String>();
            for (var future : futures) results.add(future.get(30, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("SUCCESS"::equals).count());
            assertEquals(9, results.stream().filter("SEAT_ALREADY_BOOKED"::equals).count());
        }
        assertEquals(1, bookings.count());
    }

    @Test
    void completeTcpDemoFlowCreatesEventContendsAndShowsPrivateHistory() throws Exception {
        long eventId;
        long seatId;
        try (var admin = client()) {
            var event = json.createObjectNode()
                .put("title", "Acceptance Demo")
                .put("description", "Created through the real TCP protocol")
                .put("venue", "Main Hall")
                .put("startsAt", Instant.now().plus(Duration.ofDays(2)).toString())
                .put("capacity", 2);
            var created = admin.sendSync(RequestEnvelope.of("CREATE_EVENT", adminToken, event));
            assertTrue(created.isSuccess(), created.errorMessage());
            eventId = created.data().path("eventId").asLong();

            var listed = admin.sendSync(RequestEnvelope.of("LIST_EVENTS", adminToken,
                json.createObjectNode().put("page", 0).put("size", 20)));
            assertTrue(listed.isSuccess(), listed.errorMessage());
            assertTrue(listed.data().path("items").valueStream()
                .anyMatch(item -> item.path("eventId").asLong() == eventId));

            var inventory = admin.sendSync(RequestEnvelope.of("GET_SEATS", adminToken,
                json.createObjectNode().put("eventId", eventId).put("page", 0).put("size", 20)));
            assertTrue(inventory.isSuccess(), inventory.errorMessage());
            assertEquals(2, inventory.data().path("totalElements").asInt());
            seatId = inventory.data().path("items").get(0).path("seatId").asLong();
        }

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Map<String, String> outcomes = new java.util.concurrent.ConcurrentHashMap<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (String token : tokens.subList(0, 2)) futures.add(pool.submit(() -> {
                try (var student = client()) {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    var response = student.sendSync(RequestEnvelope.of("BOOK_SEAT", token,
                        json.createObjectNode().put("eventId", eventId).put("seatId", seatId)));
                    outcomes.put(token, response.isSuccess() ? "SUCCESS" : response.errorCode());
                }
                return null;
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }

        assertEquals(1, outcomes.values().stream().filter("SUCCESS"::equals).count());
        assertEquals(1, outcomes.values().stream().filter("SEAT_ALREADY_BOOKED"::equals).count());
        for (String token : tokens.subList(0, 2)) try (var student = client()) {
            var history = student.sendSync(RequestEnvelope.of("MY_BOOKINGS", token,
                json.createObjectNode().put("page", 0).put("size", 20)));
            assertTrue(history.isSuccess(), history.errorMessage());
            assertEquals("SUCCESS".equals(outcomes.get(token)) ? 1 : 0,
                history.data().path("totalElements").asInt());
        }
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM bookings WHERE event_id = ?",
            Long.class, eventId));
    }

    private TcpClient client() throws Exception {
        var client = new TcpClient("127.0.0.1", server.getPort(), Duration.ofSeconds(15), false);
        client.connect();
        return client;
    }
}
