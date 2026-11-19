package com.unievents.unievents_backend.network.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unievents.unievents_backend.notification.UdpSubscriptionManager;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded UDP hint listener. Callers must issue GET_SEATS for every delivered signal. */
public final class UdpSeatChangeListener implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(UdpSeatChangeListener.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DatagramSocket socket;
    private final Consumer<SeatRefreshSignal> callback;
    private final AtomicReference<String> subscriptionId = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final ConcurrentHashMap<Long, StreamPosition> positions = new ConcurrentHashMap<>();
    private Thread thread;

    public UdpSeatChangeListener(int port, Consumer<SeatRefreshSignal> callback) throws SocketException {
        this.socket = new DatagramSocket(port);
        this.callback = Objects.requireNonNull(callback, "callback must not be null");
    }

    public int getLocalPort() { return socket.getLocalPort(); }

    public void setSubscriptionId(String value) {
        subscriptionId.set(value == null || value.isBlank() ? null : value.strip());
        positions.clear();
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) return;
        thread = new Thread(this::receiveLoop, "udp-seat-change-listener-" + getLocalPort());
        thread.setDaemon(true);
        thread.start();
    }

    private void receiveLoop() {
        while (running.get()) {
            byte[] bytes = new byte[UdpSubscriptionManager.MAX_DATAGRAM_BYTES + 1];
            var packet = new DatagramPacket(bytes, bytes.length);
            try {
                socket.receive(packet);
                if (packet.getLength() > UdpSubscriptionManager.MAX_DATAGRAM_BYTES) continue;
                handle(packet);
            } catch (SocketException ex) {
                if (running.get()) log.warn("UDP listener socket failed: {}", ex.getMessage());
            } catch (Exception ex) {
                log.debug("Ignored invalid UDP seat notification: {}", ex.getMessage());
            }
        }
    }

    private void handle(DatagramPacket packet) throws IOException {
        var node = JSON.readTree(packet.getData(), packet.getOffset(), packet.getLength());
        if (!node.isObject() || !"SEAT_CHANGED".equals(node.path("type").asText())) return;
        String expected = subscriptionId.get();
        String actual = node.path("subscriptionId").asText();
        if (expected == null || !expected.equals(actual)) return;
        String stream = requiredText(node.path("streamId").asText(), "streamId");
        long eventId = positive(node.path("eventId").asLong(), "eventId");
        long seatId = positive(node.path("seatId").asLong(), "seatId");
        long sequence = positive(node.path("sequence").asLong(), "sequence");
        if (!"BOOKED".equals(node.path("seatStatus").asText())) return;
        Instant timestamp = Instant.parse(node.path("timestamp").asText());
        var notification = new SeatChangeNotification(actual, stream, eventId, seatId, "BOOKED", sequence, timestamp);
        StreamPosition previous = positions.get(eventId);
        boolean streamChanged = previous != null && !previous.streamId().equals(stream);
        boolean gap = previous != null && !streamChanged && sequence != previous.sequence() + 1;
        if (previous != null && !streamChanged && sequence <= previous.sequence()) return;
        positions.put(eventId, new StreamPosition(stream, sequence));
        callback.accept(new SeatRefreshSignal(notification, streamChanged, gap));
    }

    private static long positive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    @Override
    public synchronized void close() {
        running.set(false);
        socket.close();
        if (thread != null) thread.interrupt();
    }

    private record StreamPosition(String streamId, long sequence) {}
}
