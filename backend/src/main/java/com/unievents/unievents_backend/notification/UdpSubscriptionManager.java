package com.unievents.unievents_backend.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.booking.BookingCommitted;
import com.unievents.unievents_backend.domain.service.SessionManager;
import com.unievents.unievents_backend.event.EventRepository;
import jakarta.annotation.PreDestroy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class UdpSubscriptionManager {
    public static final int MAX_DATAGRAM_BYTES = 1_200;
    private static final Logger log = LoggerFactory.getLogger(UdpSubscriptionManager.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final EventRepository events;
    private final SessionManager sessions;
    private final Duration ttl;
    private final String streamId = UUID.randomUUID().toString();
    private final ConcurrentHashMap<String, UdpSubscription> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> idByEndpoint = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "udp-notification-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final DatagramSocket socket;

    public UdpSubscriptionManager(EventRepository events, SessionManager sessions,
            @Value("${UDP_SUBSCRIPTION_TTL_SECONDS:60}") long ttlSeconds) throws java.net.SocketException {
        this.events = events;
        this.sessions = sessions;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
        this.socket = new DatagramSocket();
        worker.scheduleAtFixedRate(this::pruneSafely, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized SubscriptionResult subscribe(String token, Long userId, Long eventId, InetAddress address, int port) {
        if (!events.existsById(eventId)) throw new SubscriptionException("EVENT_NOT_FOUND", "Event not found.");
        Instant expiresAt = Instant.now().plus(ttl);
        String endpointKey = token + '|' + eventId + '|' + address.getHostAddress() + '|' + port;
        String id = idByEndpoint.compute(endpointKey, (key, existingId) -> {
            if (existingId != null && byId.containsKey(existingId)) return existingId;
            return UUID.randomUUID().toString();
        });
        byId.put(id, new UdpSubscription(id, token, userId, eventId, address, port, expiresAt, endpointKey));
        return new SubscriptionResult(id, eventId, expiresAt, streamId);
    }

    public boolean unsubscribe(String token, String subscriptionId) {
        UdpSubscription current = byId.get(subscriptionId);
        if (current == null || !current.token().equals(token)) return false;
        return remove(current);
    }

    public void removeSession(String token) {
        if (token == null) return;
        byId.values().stream().filter(value -> token.equals(value.token())).toList().forEach(this::remove);
    }

    public int activeCount() {
        prune();
        return byId.size();
    }

    @EventListener
    public void bookingCommitted(BookingCommitted event) {
        worker.execute(() -> sendChange(event));
    }

    private void sendChange(BookingCommitted event) {
        try {
            prune();
            var targets = byId.values().stream().filter(value -> value.eventId().equals(event.eventId())).toList();
            if (targets.isEmpty()) return;
            long sequence = sequences.computeIfAbsent(event.eventId(), ignored -> new AtomicLong()).incrementAndGet();
            for (UdpSubscription target : targets) send(target, event, sequence);
        } catch (RuntimeException ex) {
            log.error("Failed to process UDP notification for booking {}", event.bookingId(), ex);
        }
    }

    private void send(UdpSubscription target, BookingCommitted event, long sequence) {
        try {
            ObjectNode message = JSON.createObjectNode();
            message.put("type", "SEAT_CHANGED");
            message.put("subscriptionId", target.subscriptionId());
            message.put("streamId", streamId);
            message.put("eventId", event.eventId());
            message.put("seatId", event.seatId());
            message.put("seatStatus", "BOOKED");
            message.put("sequence", sequence);
            message.put("timestamp", event.committedAt().toString());
            byte[] bytes = JSON.writeValueAsBytes(message);
            if (bytes.length > MAX_DATAGRAM_BYTES) throw new IllegalStateException("UDP notification exceeds 1200 bytes");
            socket.send(new DatagramPacket(bytes, bytes.length, target.address(), target.port()));
        } catch (Exception ex) {
            log.warn("UDP delivery failed for subscription {}: {}", target.subscriptionId(), ex.getMessage());
        }
    }

    private void pruneSafely() {
        try { prune(); } catch (RuntimeException ex) { log.warn("UDP subscription cleanup failed", ex); }
    }

    private void prune() {
        Instant now = Instant.now();
        byId.values().stream()
            .filter(value -> value.isExpired(now) || !sessions.isActive(value.token()))
            .toList().forEach(this::remove);
    }

    private boolean remove(UdpSubscription value) {
        boolean removed = byId.remove(value.subscriptionId(), value);
        if (removed) idByEndpoint.remove(value.endpointKey(), value.subscriptionId());
        return removed;
    }

    @PreDestroy
    void close() {
        worker.shutdownNow();
        socket.close();
        byId.clear();
        idByEndpoint.clear();
    }
}
