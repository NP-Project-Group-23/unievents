package com.unievents.unievents_backend.notification;

import java.net.InetAddress;
import java.time.Instant;

record UdpSubscription(String subscriptionId, String token, Long userId, Long eventId,
                       InetAddress address, int port, Instant expiresAt, String endpointKey) {
    boolean isExpired(Instant now) { return !expiresAt.isAfter(now); }
}
