package com.unievents.unievents_backend.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.booking.*;
import com.unievents.unievents_backend.domain.model.*;
import com.unievents.unievents_backend.domain.service.SessionManager;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.protocol.*;
import com.unievents.unievents_backend.network.server.TcpServer;
import com.unievents.unievents_backend.seat.*;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:event-integration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.flyway.enabled=true", "spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=validate"
})
class EventIntegrationTests {
    @Autowired EventService events;
    @Autowired EventRepository repository;
    @Autowired SeatRepository seats;
    @Autowired BookingService bookings;
    @Autowired JdbcTemplate db;
    @Autowired SessionManager sessions;
    @Autowired TcpServer server;
    @Autowired PlatformTransactionManager transactions;
    Long adminId;
    Long studentId;
    String adminToken;
    String studentToken;
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setup() {
        db.update("DELETE FROM bookings"); db.update("DELETE FROM seats");
        db.update("DELETE FROM events"); db.update("DELETE FROM app_users");
        db.update("INSERT INTO app_users(name,email,password_hash,role) VALUES ('Admin','admin@test.com','hash','ADMIN'),('Student','student@test.com','hash','STUDENT')");
        adminId = db.queryForObject("SELECT id FROM app_users WHERE role='ADMIN'",Long.class);
        studentId = db.queryForObject("SELECT id FROM app_users WHERE role='STUDENT'",Long.class);
        adminToken = sessions.createSession(new User(adminId,"Admin","admin@test.com","hash",Role.ADMIN,Instant.now())).token();
        studentToken = sessions.createSession(new User(studentId,"Student","student@test.com","hash",Role.STUDENT,Instant.now())).token();
    }

    private ObjectNode input(int capacity) {
        return json.createObjectNode().put("title","Campus concert").put("venue","Main hall")
            .put("startsAt",Instant.now().plusSeconds(86400).toString()).put("capacity",capacity);
    }
    private EventResult create(int capacity) {
        return events.create(adminId,"Campus concert","","Main hall",Instant.now().plusSeconds(86400),capacity);
    }

    @Test
    void tcpCrudAndPermissions() throws Exception {
        try (var client = new TcpClient("127.0.0.1",server.getPort(),Duration.ofSeconds(5),false)) {
            client.connect();
            assertEquals("UNAUTHENTICATED",client.sendSync(RequestEnvelope.of("LIST_EVENTS",json.createObjectNode())).errorCode());
            assertEquals("FORBIDDEN",client.sendSync(RequestEnvelope.of("CREATE_EVENT",studentToken,input(40))).errorCode());
            var result = client.sendSync(RequestEnvelope.of("CREATE_EVENT",adminToken,input(40)));
            assertTrue(result.isSuccess(),result.errorMessage());
            long id = result.data().path("eventId").asLong();
            assertEquals(adminId.longValue(),result.data().path("createdBy").asLong());
            assertEquals(40,seats.findByEventIdOrderBySeatNumberAsc(id).size());
            assertTrue(seats.findByEventIdOrderBySeatNumberAsc(id).stream().anyMatch(seat -> seat.getSeatNumber().equals("E8")));
            var key = json.createObjectNode().put("eventId",id);
            assertTrue(client.sendSync(RequestEnvelope.of("GET_EVENT",studentToken,key)).isSuccess());
            var page = client.sendSync(RequestEnvelope.of("LIST_EVENTS",studentToken,json.createObjectNode()));
            assertEquals(1,page.data().path("totalElements").asInt());
            assertEquals("FORBIDDEN",client.sendSync(RequestEnvelope.of("DELETE_EVENT",studentToken,key)).errorCode());
            var update = input(40).put("eventId",id).put("title","Updated title");
            assertEquals("VALIDATION_FAILED",client.sendSync(RequestEnvelope.of("UPDATE_EVENT",adminToken,update)).errorCode());
            update.remove("capacity");
            assertTrue(client.sendSync(RequestEnvelope.of("UPDATE_EVENT",adminToken,update)).isSuccess());
            assertEquals("Updated title",events.get(id).title());
            assertTrue(client.sendSync(RequestEnvelope.of("DELETE_EVENT",adminToken,key)).isSuccess());
            assertTrue(seats.findByEventIdOrderBySeatNumberAsc(id).isEmpty());
            assertEquals("EVENT_NOT_FOUND",client.sendSync(RequestEnvelope.of("GET_EVENT",studentToken,key)).errorCode());
        }
    }

    @Test
    void validationAndServiceAuthorization() {
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(EventException.class,() -> events.create(studentId,"Title","","Hall",Instant.now().plusSeconds(100),1)).getCode());
        assertThrows(EventException.class,() -> create(0));
        assertThrows(EventException.class,() -> create(1001));
        assertThrows(EventException.class,() -> events.create(adminId," ","","Hall",Instant.now().plusSeconds(100),1));
        assertThrows(EventException.class,() -> events.create(adminId,"Title","","Hall",Instant.now().minusSeconds(1),1));
        assertThrows(EventException.class,() -> events.list(-1,20));
        assertEquals(0,repository.count());
        assertEquals(0,seats.count());
    }

    @Test
    void transactionRollbackRemovesEventAndGeneratedSeats() {
        new TransactionTemplate(transactions).execute(status -> {
            create(40);
            assertEquals(40,seats.count());
            status.setRollbackOnly();
            return null;
        });
        assertEquals(0,repository.count());
        assertEquals(0,seats.count());
    }

    @Test
    void seatInsertionFailureRollsBackEntireCreation() {
        db.execute("ALTER TABLE seats ADD CONSTRAINT test_reject_second_seat CHECK (seat_number <> 'A2')");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> create(3));
            assertEquals(0,repository.count());
            assertEquals(0,seats.count());
        } finally {
            db.execute("ALTER TABLE seats DROP CONSTRAINT test_reject_second_seat");
        }
    }

    @Test
    void bookedEventCannotBeDeletedAndPastEventCannotBeBooked() {
        var event = create(2);
        var inventory = seats.findByEventIdOrderBySeatNumberAsc(event.eventId());
        bookings.bookSeat(studentId,new BookingRequest(event.eventId(),inventory.getFirst().getId()));
        assertEquals(ErrorCode.VALIDATION_FAILED,assertThrows(EventException.class,() -> events.delete(adminId,event.eventId())).getCode());
        assertEquals(2,seats.findByEventIdOrderBySeatNumberAsc(event.eventId()).size());
        db.update("UPDATE events SET starts_at=? WHERE id=?",OffsetDateTime.now().minusDays(1),event.eventId());
        assertEquals(BookingException.Code.VALIDATION_FAILED,assertThrows(BookingException.class,
            () -> bookings.bookSeat(studentId,new BookingRequest(event.eventId(),inventory.getLast().getId()))).getCode());
        assertEquals(Seat.Status.AVAILABLE,seats.findById(inventory.getLast().getId()).orElseThrow().getStatus());
    }
}
