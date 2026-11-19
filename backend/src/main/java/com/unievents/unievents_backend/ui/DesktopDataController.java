package com.unievents.unievents_backend.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.client.UdpSeatChangeListener;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import java.net.SocketException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Network orchestration for the desktop views; all mutations still go through TCP. */
public final class DesktopDataController implements AutoCloseable {
    private final TcpClient tcp;
    private final SessionHolder sessions;
    private final ObjectMapper json;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "desktop-seat-reconciliation");
        thread.setDaemon(true);
        return thread;
    });
    private UdpSeatChangeListener udp;
    private ScheduledFuture<?> renewal;
    private ScheduledFuture<?> reconciliation;
    private volatile String subscriptionId;
    private volatile Long watchedEventId;
    private volatile int watchedPage;
    private volatile int watchedSize;
    private volatile Consumer<ResponseEnvelope> seatConsumer;

    public DesktopDataController(TcpClient tcp, SessionHolder sessions, ObjectMapper json) {
        this.tcp = Objects.requireNonNull(tcp);
        this.sessions = Objects.requireNonNull(sessions);
        this.json = json != null ? json : new ObjectMapper();
    }

    public CompletableFuture<ResponseEnvelope> listEvents(int page, int size) {
        return send("LIST_EVENTS", page(page, size));
    }

    public CompletableFuture<ResponseEnvelope> getSeats(long eventId, int page, int size) {
        return send("GET_SEATS", page(page, size).put("eventId", eventId));
    }

    public CompletableFuture<ResponseEnvelope> book(long eventId, long seatId) {
        return send("BOOK_SEAT", json.createObjectNode().put("eventId", eventId).put("seatId", seatId));
    }

    public CompletableFuture<ResponseEnvelope> myBookings(int page, int size) {
        return send("MY_BOOKINGS", page(page, size));
    }

    public CompletableFuture<ResponseEnvelope> createEvent(String title, String description, String venue,
            String startsAt, int capacity) {
        return send("CREATE_EVENT", json.createObjectNode().put("title", title).put("description", description)
            .put("venue", venue).put("startsAt", startsAt).put("capacity", capacity));
    }

    public CompletableFuture<ResponseEnvelope> updateEvent(long eventId, String title, String description,
            String venue, String startsAt) {
        return send("UPDATE_EVENT", json.createObjectNode().put("eventId", eventId).put("title", title)
            .put("description", description).put("venue", venue).put("startsAt", startsAt));
    }

    public CompletableFuture<ResponseEnvelope> deleteEvent(long eventId) {
        return send("DELETE_EVENT", json.createObjectNode().put("eventId", eventId));
    }

    public synchronized CompletableFuture<ResponseEnvelope> watchSeats(long eventId, int page, int size,
            Consumer<ResponseEnvelope> consumer) {
        stopWatching();
        watchedEventId = eventId;
        watchedPage = page;
        watchedSize = size;
        seatConsumer = Objects.requireNonNull(consumer);
        try {
            udp = new UdpSeatChangeListener(0, ignored -> refreshWatchedSeats());
            udp.start();
        } catch (SocketException ex) {
            return CompletableFuture.failedFuture(ex);
        }
        CompletableFuture<ResponseEnvelope> initial = refreshWatchedSeats();
        subscribe().thenAccept(response -> {
            UdpSeatChangeListener listener = udp;
            if (response != null && response.isSuccess() && listener != null) {
                subscriptionId = response.data().path("subscriptionId").asText();
                listener.setSubscriptionId(subscriptionId);
                refreshWatchedSeats();
            }
        });
        renewal = scheduler.scheduleAtFixedRate(this::subscribeSafely, 30, 30, TimeUnit.SECONDS);
        reconciliation = scheduler.scheduleAtFixedRate(this::refreshWatchedSeats, 15, 15, TimeUnit.SECONDS);
        return initial;
    }

    public synchronized void changeSeatPage(int page) {
        if (page < 0 || watchedEventId == null) return;
        watchedPage = page;
        refreshWatchedSeats();
    }

    private CompletableFuture<ResponseEnvelope> subscribe() {
        UdpSeatChangeListener listener = udp;
        Long eventId = watchedEventId;
        if (listener == null || eventId == null) return CompletableFuture.completedFuture(null);
        ObjectNode payload = json.createObjectNode().put("eventId", eventId).put("udpPort", listener.getLocalPort());
        return send("SUBSCRIBE_EVENT", payload);
    }

    private void subscribeSafely() {
        subscribe().exceptionally(ignored -> null);
    }

    private CompletableFuture<ResponseEnvelope> refreshWatchedSeats() {
        Long eventId = watchedEventId;
        Consumer<ResponseEnvelope> consumer = seatConsumer;
        if (eventId == null || consumer == null) return CompletableFuture.completedFuture(null);
        return getSeats(eventId, watchedPage, watchedSize).thenApply(response -> {
            consumer.accept(response);
            return response;
        }).exceptionally(ex -> {
            consumer.accept(null);
            return null;
        });
    }

    public synchronized void stopWatching() {
        if (renewal != null) renewal.cancel(false);
        if (reconciliation != null) reconciliation.cancel(false);
        renewal = null;
        reconciliation = null;
        String oldId = subscriptionId;
        subscriptionId = null;
        if (oldId != null && sessions.isAuthenticated()) {
            send("UNSUBSCRIBE_EVENT", json.createObjectNode().put("subscriptionId", oldId)).exceptionally(ignored -> null);
        }
        if (udp != null) udp.close();
        udp = null;
        watchedEventId = null;
        seatConsumer = null;
    }

    public CompletableFuture<ResponseEnvelope> logout() {
        return send("LOGOUT", json.createObjectNode());
    }

    private CompletableFuture<ResponseEnvelope> send(String operation, JsonNode payload) {
        String token = sessions.getToken();
        if (token == null) return CompletableFuture.failedFuture(new IllegalStateException("No active session"));
        return tcp.sendAsync(RequestEnvelope.of(operation, token, payload));
    }

    private ObjectNode page(int page, int size) {
        return json.createObjectNode().put("page", page).put("size", size);
    }

    @Override
    public synchronized void close() {
        stopWatching();
        scheduler.shutdownNow();
    }
}
