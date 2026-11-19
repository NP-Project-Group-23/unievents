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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:booking-protocol;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.enabled=true", "spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=validate"
})
@Import(BookingProtocolIntegrationTests.EventTestConfig.class)
class BookingProtocolIntegrationTests {
    @Autowired BookingService service;
    @Autowired BookingRepository bookings;
    @Autowired SeatRepository seats;
    @Autowired JdbcTemplate db;
    @Autowired SessionManager sessions;
    @Autowired TcpServer server;
    @Autowired CommitProbe probe;

    private final ObjectMapper json = new ObjectMapper();
    private Long firstUserId;
    private Long secondUserId;
    private String firstToken;
    private String secondToken;
    private Seat firstSeat;
    private Seat secondSeat;

    @BeforeEach
    void setup() {
        probe.reset();
        sessions.clear();
        db.update("DELETE FROM bookings");
        db.update("DELETE FROM seats");
        db.update("DELETE FROM events");
        db.update("DELETE FROM app_users");
        db.update("INSERT INTO app_users(name,email,password_hash,role) VALUES "
            + "('One','one@test.com','hash','STUDENT'),('Two','two@test.com','hash','STUDENT')");
        firstUserId = db.queryForObject("SELECT id FROM app_users WHERE email='one@test.com'", Long.class);
        secondUserId = db.queryForObject("SELECT id FROM app_users WHERE email='two@test.com'", Long.class);
        db.update("INSERT INTO events(id,title,description,venue,starts_at,capacity,created_by) "
                + "OVERRIDING SYSTEM VALUE VALUES (10,'Concert','','Hall',?,3,?)",
            OffsetDateTime.now().plusDays(2), firstUserId);
        firstSeat = seats.saveAndFlush(new Seat(10L, "A1"));
        secondSeat = seats.saveAndFlush(new Seat(10L, "A2"));
        seats.saveAndFlush(new Seat(10L, "A3"));
        firstToken = sessions.createSession(user(firstUserId, "One", "one@test.com")).token();
        secondToken = sessions.createSession(user(secondUserId, "Two", "two@test.com")).token();
    }

    @Test
    void realTcpBookingFlowIsAuthenticatedPrivateAndAtomic() throws Exception {
        try (var client = client()) {
            assertEquals("UNAUTHENTICATED", send(client, "GET_SEATS", null,
                json.createObjectNode().put("eventId", 10)).errorCode());

            var page = send(client, "GET_SEATS", firstToken,
                json.createObjectNode().put("eventId", 10).put("page", 0).put("size", 2));
            assertTrue(page.isSuccess(), page.errorMessage());
            assertEquals(3, page.data().path("totalElements").asInt());
            assertEquals(2, page.data().path("items").size());
            assertEquals(firstSeat.getId().longValue(), page.data().path("items").get(0).path("seatId").asLong());

            var request = json.createObjectNode().put("eventId", 10).put("seatId", firstSeat.getId())
                .put("userId", secondUserId); // Deliberately ignored: identity comes from the session.
            var booked = send(client, "BOOK_SEAT", firstToken, request);
            assertTrue(booked.isSuccess(), booked.errorMessage());
            long bookingId = booked.data().path("bookingId").asLong();
            assertEquals(firstUserId, bookings.findById(bookingId).orElseThrow().getUserId());
            assertEquals(1, probe.events.size());
            assertEquals(bookingId, probe.events.getFirst().bookingId());

            assertEquals("SEAT_ALREADY_BOOKED", send(client, "BOOK_SEAT", secondToken, request).errorCode());
            assertEquals(1, bookings.count());
            assertEquals(1, probe.events.size());

            var mine = send(client, "MY_BOOKINGS", firstToken, json.createObjectNode());
            assertEquals(1, mine.data().path("totalElements").asInt());
            var bookingKey = json.createObjectNode().put("bookingId", bookingId);
            assertTrue(send(client, "GET_BOOKING", firstToken, bookingKey).isSuccess());
            assertEquals("BOOKING_NOT_FOUND", send(client, "GET_BOOKING", secondToken, bookingKey).errorCode());
            assertEquals(0, send(client, "MY_BOOKINGS", secondToken, json.createObjectNode())
                .data().path("totalElements").asInt());
        }
    }

    @Test
    void protocolValidatesIdentifiersPaginationAndMissingEvents() throws Exception {
        try (var client = client()) {
            assertEquals("INVALID_REQUEST", send(client, "GET_SEATS", firstToken,
                json.createObjectNode().put("eventId", 0)).errorCode());
            assertEquals("INVALID_REQUEST", send(client, "GET_SEATS", firstToken,
                json.createObjectNode().put("eventId", 10).put("size", 101)).errorCode());
            assertEquals("EVENT_NOT_FOUND", send(client, "GET_SEATS", firstToken,
                json.createObjectNode().put("eventId", 999)).errorCode());
            assertEquals("INVALID_REQUEST", send(client, "BOOK_SEAT", firstToken,
                json.createObjectNode().put("eventId", 10)).errorCode());
            assertEquals("INVALID_REQUEST", send(client, "GET_BOOKING", firstToken,
                json.createObjectNode().put("bookingId", -1)).errorCode());
        }
    }

    @Test
    void commitEventsAreSuppressedOnRollbackAndListenerFailureCannotUndoBooking() {
        assertThrows(DataIntegrityViolationException.class,
            () -> service.bookSeat(Long.MAX_VALUE, new BookingRequest(10L, firstSeat.getId())));
        assertTrue(probe.events.isEmpty());
        assertEquals(Seat.Status.AVAILABLE, seats.findById(firstSeat.getId()).orElseThrow().getStatus());

        probe.fail.set(true);
        var result = service.bookSeat(firstUserId, new BookingRequest(10L, secondSeat.getId()));
        assertNotNull(result.bookingId());
        assertEquals(1, bookings.count());
        assertEquals(1, probe.events.size());
        assertEquals(Seat.Status.BOOKED, seats.findById(secondSeat.getId()).orElseThrow().getStatus());
    }

    private TcpClient client() throws Exception {
        var client = new TcpClient("127.0.0.1", server.getPort(), Duration.ofSeconds(5), false);
        client.connect();
        return client;
    }

    private com.unievents.unievents_backend.network.protocol.ResponseEnvelope send(
            TcpClient client, String operation, String token, com.fasterxml.jackson.databind.JsonNode payload) throws Exception {
        return token == null
            ? client.sendSync(RequestEnvelope.of(operation, payload))
            : client.sendSync(RequestEnvelope.of(operation, token, payload));
    }

    private static User user(Long id, String name, String email) {
        return new User(id, name, email, "hash", Role.STUDENT, Instant.now());
    }

    @TestConfiguration
    static class EventTestConfig {
        @Bean CommitProbe commitProbe() { return new CommitProbe(); }
    }

    static class CommitProbe {
        final List<BookingCommitted> events = new CopyOnWriteArrayList<>();
        final AtomicBoolean fail = new AtomicBoolean();

        @EventListener
        void onCommit(BookingCommitted event) {
            events.add(event);
            if (fail.get()) throw new IllegalStateException("simulated notification failure");
        }

        void reset() {
            events.clear();
            fail.set(false);
        }
    }
}
