package com.unievents.unievents_backend.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.time.Duration;
import javafx.scene.control.TabPane;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DesktopDataControllerTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void buildsAuthenticatedStudentAndAdminRequests() {
        TcpClient tcp = mock(TcpClient.class);
        List<RequestEnvelope> requests = new CopyOnWriteArrayList<>();
        when(tcp.sendAsync(any())).thenAnswer(invocation -> {
            RequestEnvelope request = invocation.getArgument(0);
            requests.add(request);
            return CompletableFuture.completedFuture(ResponseEnvelope.success(request.requestId(), json.createObjectNode()));
        });
        SessionHolder holder = session(Role.ADMIN);
        try (var controller = new DesktopDataController(tcp, holder, json)) {
            controller.listEvents(2, 20).join();
            controller.getSeats(9, 1, 50).join();
            controller.book(9, 12).join();
            controller.myBookings(0, 10).join();
            controller.createEvent("Title", "Description", "Hall", "2030-01-01T10:00:00Z", 40).join();
            controller.updateEvent(9, "Updated", "", "New hall", "2030-01-02T10:00:00Z").join();
            controller.deleteEvent(9).join();
        }
        assertEquals(List.of("LIST_EVENTS", "GET_SEATS", "BOOK_SEAT", "MY_BOOKINGS",
            "CREATE_EVENT", "UPDATE_EVENT", "DELETE_EVENT"), requests.stream().map(RequestEnvelope::type).toList());
        assertTrue(requests.stream().allMatch(request -> "desktop-token".equals(request.token())));
        assertEquals(2, requests.get(0).payload().path("page").asInt());
        assertEquals(12, requests.get(2).payload().path("seatId").asLong());
        assertEquals(40, requests.get(4).payload().path("capacity").asInt());
        assertFalse(requests.get(5).payload().has("capacity"));
    }

    @Test
    void watchingRegistersUdpRefreshesSeatsAndUnsubscribesOnClose() {
        TcpClient tcp = mock(TcpClient.class);
        List<RequestEnvelope> requests = new CopyOnWriteArrayList<>();
        when(tcp.sendAsync(any())).thenAnswer(invocation -> {
            RequestEnvelope request = invocation.getArgument(0);
            requests.add(request);
            var data = json.createObjectNode();
            if (request.type().equals("SUBSCRIBE_EVENT")) data.put("subscriptionId", "sub-1");
            return CompletableFuture.completedFuture(ResponseEnvelope.success(request.requestId(), data));
        });
        var responses = new CopyOnWriteArrayList<ResponseEnvelope>();
        var controller = new DesktopDataController(tcp, session(Role.STUDENT), json);
        controller.watchSeats(7, 0, 100, responses::add).join();
        controller.changeSeatPage(1);
        controller.stopWatching();
        controller.close();

        assertTrue(requests.stream().anyMatch(request -> request.type().equals("SUBSCRIBE_EVENT")
            && request.payload().path("eventId").asLong() == 7
            && request.payload().path("udpPort").asInt() > 0));
        assertTrue(requests.stream().anyMatch(request -> request.type().equals("GET_SEATS")
            && request.payload().path("page").asInt() == 1));
        assertTrue(requests.stream().anyMatch(request -> request.type().equals("UNSUBSCRIBE_EVENT")
            && request.payload().path("subscriptionId").asText().equals("sub-1")));
        assertFalse(responses.isEmpty());
    }

    @Test
    void refusesRequestsWithoutClientSession() {
        TcpClient tcp = mock(TcpClient.class);
        try (var controller = new DesktopDataController(tcp, new SessionHolder(), json)) {
            assertThrows(Exception.class, () -> controller.listEvents(0, 20).join());
        }
        verify(tcp, never()).sendAsync(any());
    }

    @Test
    void reconnectValidatesSessionBeforeRefreshingDesktop() {
        TcpClient tcp = mock(TcpClient.class);
        var listener = org.mockito.ArgumentCaptor.forClass(
            com.unievents.unievents_backend.network.client.ConnectionListener.class);
        List<RequestEnvelope> requests = new CopyOnWriteArrayList<>();
        when(tcp.sendAsync(any())).thenAnswer(invocation -> {
            RequestEnvelope request = invocation.getArgument(0);
            requests.add(request);
            return CompletableFuture.completedFuture(ResponseEnvelope.success(request.requestId(), json.createObjectNode()));
        });
        var recovered = new java.util.concurrent.atomic.AtomicBoolean();
        try (var controller = new DesktopDataController(tcp, session(Role.STUDENT), json)) {
            controller.setOnReconnected(() -> recovered.set(true));
            verify(tcp).addConnectionListener(listener.capture());
            listener.getValue().onConnectionStateChanged(
                com.unievents.unievents_backend.network.client.ConnectionState.RECONNECTING,
                com.unievents.unievents_backend.network.client.ConnectionState.CONNECTED);
        }
        assertTrue(recovered.get());
        assertEquals("SESSION_CHECK", requests.getFirst().type());
    }

    @Test
    void periodicTcpReconciliationRecoversWhenNoUdpPacketArrives() throws Exception {
        TcpClient tcp = mock(TcpClient.class);
        List<RequestEnvelope> requests = new CopyOnWriteArrayList<>();
        when(tcp.sendAsync(any())).thenAnswer(invocation -> {
            RequestEnvelope request = invocation.getArgument(0);
            requests.add(request);
            var data = json.createObjectNode();
            if (request.type().equals("SUBSCRIBE_EVENT")) data.put("subscriptionId", "loss-test");
            return CompletableFuture.completedFuture(ResponseEnvelope.success(request.requestId(), data));
        });
        try (var controller = new DesktopDataController(tcp, session(Role.STUDENT), json,
                Duration.ofSeconds(5), Duration.ofMillis(40))) {
            controller.watchSeats(8, 0, 20, ignored -> {}).join();
            Thread.sleep(130);
        }
        assertTrue(requests.stream().filter(request -> request.type().equals("GET_SEATS")).count() >= 3,
            "TCP polling must recover even when no UDP hint is delivered");
    }

    @Test
    void successfulLoginMountsRoleAwareDashboard() throws Exception {
        TcpClient tcp = mock(TcpClient.class);
        when(tcp.getState()).thenReturn(com.unievents.unievents_backend.network.client.ConnectionState.CONNECTED);
        when(tcp.sendAsync(any())).thenAnswer(invocation -> {
            RequestEnvelope request = invocation.getArgument(0);
            var data = json.createObjectNode();
            data.putArray("items");
            data.put("page", 0).put("size", 100).put("totalElements", 0).put("totalPages", 0);
            return CompletableFuture.completedFuture(ResponseEnvelope.success(request.requestId(), data));
        });
        JavaFxTestHelper.runAndWait(() -> {
            var coordinator = new AuthUiCoordinator(tcp, json);
            var login = json.createObjectNode().put("userId", 5).put("name", "Admin")
                .put("email", "admin@test").put("role", "ADMIN");
            coordinator.getLoginView().getOnLoginSuccess().accept("desktop-token", login);
            assertEquals(AuthUiCoordinator.ViewType.DASHBOARD, coordinator.getCurrentViewType());
            assertSame(coordinator.getDashboardView(), coordinator.getCurrentView());
            assertEquals(3, ((TabPane) coordinator.getDashboardView().getCenter()).getTabs().size());
            coordinator.getDashboardView().close();
        });
    }

    private static SessionHolder session(Role role) {
        var holder = new SessionHolder();
        holder.setSession("desktop-token", 4L, "Desktop User", "desktop@test", role);
        return holder;
    }
}
