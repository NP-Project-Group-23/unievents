package com.unievents.unievents_backend.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.model.User;
import com.unievents.unievents_backend.domain.service.SessionManager;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.server.TcpServer;
import com.unievents.unievents_backend.seat.Seat;
import com.unievents.unievents_backend.seat.SeatRepository;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:udp-subscriptions;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.enabled=true", "spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=validate",
    "UDP_SUBSCRIPTION_TTL_SECONDS=1"
})
class UdpSubscriptionIntegrationTests {
    @Autowired TcpServer server;
    @Autowired SessionManager sessions;
    @Autowired SeatRepository seats;
    @Autowired JdbcTemplate db;
    @Autowired UdpSubscriptionManager subscriptions;
    private final ObjectMapper json = new ObjectMapper();
    private String firstToken;
    private String secondToken;
    private Seat firstSeat;
    private Seat secondSeat;
    private Seat thirdSeat;

    @BeforeEach
    void setup() {
        sessions.clear();
        db.update("DELETE FROM bookings");
        db.update("DELETE FROM seats");
        db.update("DELETE FROM events");
        db.update("DELETE FROM app_users");
        db.update("INSERT INTO app_users(name,email,password_hash,role) VALUES "
            + "('One','one@udp.test','hash','STUDENT'),('Two','two@udp.test','hash','STUDENT')");
        Long one = db.queryForObject("SELECT id FROM app_users WHERE email='one@udp.test'", Long.class);
        Long two = db.queryForObject("SELECT id FROM app_users WHERE email='two@udp.test'", Long.class);
        db.update("INSERT INTO events(id,title,description,venue,starts_at,capacity,created_by) "
                + "OVERRIDING SYSTEM VALUE VALUES (20,'UDP Event','','Hall',?,3,?)",
            OffsetDateTime.now().plusDays(2), one);
        firstSeat = seats.saveAndFlush(new Seat(20L, "A1"));
        secondSeat = seats.saveAndFlush(new Seat(20L, "A2"));
        thirdSeat = seats.saveAndFlush(new Seat(20L, "A3"));
        firstToken = sessions.createSession(user(one, "One", "one@udp.test")).token();
        secondToken = sessions.createSession(user(two, "Two", "two@udp.test")).token();
    }

    @Test
    void subscriptionRenewsAndReceivesSequencedIdentityFreeDatagrams() throws Exception {
        try (var udp = new DatagramSocket(0, InetAddress.getLoopbackAddress()); var tcp = client()) {
            udp.setSoTimeout(3_000);
            var subscribePayload = json.createObjectNode().put("eventId", 20)
                .put("udpPort", udp.getLocalPort()).put("host", "203.0.113.99");
            var first = tcp.sendSync(RequestEnvelope.of("SUBSCRIBE_EVENT", firstToken, subscribePayload));
            assertTrue(first.isSuccess(), first.errorMessage());
            String subscriptionId = first.data().path("subscriptionId").asText();
            String streamId = first.data().path("streamId").asText();

            var renewal = tcp.sendSync(RequestEnvelope.of("SUBSCRIBE_EVENT", firstToken, subscribePayload));
            assertEquals(subscriptionId, renewal.data().path("subscriptionId").asText());
            assertEquals(streamId, renewal.data().path("streamId").asText());

            var spoofedUnsubscribe = json.createObjectNode().put("subscriptionId", subscriptionId);
            assertFalse(tcp.sendSync(RequestEnvelope.of("UNSUBSCRIBE_EVENT", secondToken, spoofedUnsubscribe))
                .data().path("removed").asBoolean());

            book(tcp, firstToken, firstSeat);
            JsonNode firstMessage = receive(udp);
            assertEquals("SEAT_CHANGED", firstMessage.path("type").asText());
            assertEquals(subscriptionId, firstMessage.path("subscriptionId").asText());
            assertEquals(streamId, firstMessage.path("streamId").asText());
            assertEquals(20, firstMessage.path("eventId").asLong());
            assertEquals(firstSeat.getId().longValue(), firstMessage.path("seatId").asLong());
            assertEquals("BOOKED", firstMessage.path("seatStatus").asText());
            assertEquals(1, firstMessage.path("sequence").asLong());
            assertFalse(firstMessage.has("userId"));
            assertFalse(firstMessage.has("token"));

            book(tcp, firstToken, secondSeat);
            assertEquals(2, receive(udp).path("sequence").asLong());
        }
    }

    @Test
    void unsubscribeAndLogoutStopDeliveryImmediately() throws Exception {
        try (var udp = new DatagramSocket(0, InetAddress.getLoopbackAddress()); var tcp = client()) {
            udp.setSoTimeout(400);
            var payload = json.createObjectNode().put("eventId", 20).put("udpPort", udp.getLocalPort());
            String id = tcp.sendSync(RequestEnvelope.of("SUBSCRIBE_EVENT", firstToken, payload))
                .data().path("subscriptionId").asText();
            var remove = json.createObjectNode().put("subscriptionId", id);
            assertTrue(tcp.sendSync(RequestEnvelope.of("UNSUBSCRIBE_EVENT", firstToken, remove))
                .data().path("removed").asBoolean());
            assertFalse(tcp.sendSync(RequestEnvelope.of("UNSUBSCRIBE_EVENT", firstToken, remove))
                .data().path("removed").asBoolean());
            book(tcp, firstToken, firstSeat);
            assertThrows(SocketTimeoutException.class, () -> receive(udp));

            tcp.sendSync(RequestEnvelope.of("SUBSCRIBE_EVENT", firstToken, payload));
            assertEquals(1, subscriptions.activeCount());
            assertTrue(tcp.sendSync(RequestEnvelope.of("LOGOUT", firstToken, json.createObjectNode())).isSuccess());
            assertEquals(0, subscriptions.activeCount());
        }
    }

    @Test
    void validatesRequestsAndExpiresSubscriptions() throws Exception {
        try (var udp = new DatagramSocket(0, InetAddress.getLoopbackAddress()); var tcp = client()) {
            assertEquals("UNAUTHENTICATED", tcp.sendSync(RequestEnvelope.of("SUBSCRIBE_EVENT",
                json.createObjectNode().put("eventId", 20).put("udpPort", udp.getLocalPort()))).errorCode());
            assertEquals("INVALID_REQUEST", tcp.sendSync(RequestEnvelope.of("SUBSCRIBE_EVENT", firstToken,
                json.createObjectNode().put("eventId", 20).put("udpPort", 0))).errorCode());
            assertEquals("EVENT_NOT_FOUND", tcp.sendSync(RequestEnvelope.of("SUBSCRIBE_EVENT", firstToken,
                json.createObjectNode().put("eventId", 999).put("udpPort", udp.getLocalPort()))).errorCode());

            var payload = json.createObjectNode().put("eventId", 20).put("udpPort", udp.getLocalPort());
            assertTrue(tcp.sendSync(RequestEnvelope.of("SUBSCRIBE_EVENT", secondToken, payload)).isSuccess());
            assertEquals(1, subscriptions.activeCount());
            Thread.sleep(1_100);
            assertEquals(0, subscriptions.activeCount());
        }
    }

    private TcpClient client() throws Exception {
        var client = new TcpClient("127.0.0.1", server.getPort(), Duration.ofSeconds(5), false);
        client.connect();
        return client;
    }

    private void book(TcpClient client, String token, Seat seat) throws Exception {
        var payload = json.createObjectNode().put("eventId", 20).put("seatId", seat.getId());
        var response = client.sendSync(RequestEnvelope.of("BOOK_SEAT", token, payload));
        assertTrue(response.isSuccess(), response.errorMessage());
    }

    private JsonNode receive(DatagramSocket socket) throws Exception {
        byte[] buffer = new byte[UdpSubscriptionManager.MAX_DATAGRAM_BYTES + 1];
        var packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet);
        assertTrue(packet.getLength() <= UdpSubscriptionManager.MAX_DATAGRAM_BYTES);
        return json.readTree(packet.getData(), packet.getOffset(), packet.getLength());
    }

    private static User user(Long id, String name, String email) {
        return new User(id, name, email, "hash", Role.STUDENT, Instant.now());
    }
}
