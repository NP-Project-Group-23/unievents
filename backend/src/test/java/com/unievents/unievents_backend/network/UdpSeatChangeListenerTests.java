package com.unievents.unievents_backend.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unievents.unievents_backend.network.client.SeatRefreshSignal;
import com.unievents.unievents_backend.network.client.UdpSeatChangeListener;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UdpSeatChangeListenerTests {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void filtersPacketsAndDetectsGapsStreamChangesAndDuplicates() throws Exception {
        var signals = new LinkedBlockingQueue<SeatRefreshSignal>();
        try (var listener = new UdpSeatChangeListener(0, signals::add); var sender = new DatagramSocket()) {
            listener.setSubscriptionId("mine");
            listener.start();

            send(sender, listener.getLocalPort(), message("other", "stream-1", 1));
            assertNull(signals.poll(150, TimeUnit.MILLISECONDS));
            send(sender, listener.getLocalPort(), message("mine", "stream-1", 1));
            var first = signals.poll(2, TimeUnit.SECONDS);
            assertNotNull(first);
            assertFalse(first.sequenceGap());
            assertFalse(first.streamChanged());

            send(sender, listener.getLocalPort(), message("mine", "stream-1", 3));
            assertTrue(signals.poll(2, TimeUnit.SECONDS).sequenceGap());
            send(sender, listener.getLocalPort(), message("mine", "stream-1", 3));
            assertNull(signals.poll(150, TimeUnit.MILLISECONDS));

            send(sender, listener.getLocalPort(), message("mine", "stream-2", 1));
            assertTrue(signals.poll(2, TimeUnit.SECONDS).streamChanged());
        }
    }

    private byte[] message(String subscription, String stream, long sequence) throws Exception {
        var node = json.createObjectNode().put("type", "SEAT_CHANGED")
            .put("subscriptionId", subscription).put("streamId", stream)
            .put("eventId", 5).put("seatId", 7).put("seatStatus", "BOOKED")
            .put("sequence", sequence).put("timestamp", Instant.now().toString());
        return json.writeValueAsBytes(node);
    }

    private static void send(DatagramSocket sender, int port, byte[] bytes) throws Exception {
        sender.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
    }
}
