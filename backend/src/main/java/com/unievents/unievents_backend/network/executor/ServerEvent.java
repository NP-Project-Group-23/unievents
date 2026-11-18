package com.unievents.unievents_backend.network.executor;

import java.time.Instant;

/**
 * Genuine server event record capturing concurrency and connection lifecycle events.
 * Consumed by the live network monitoring dashboard (F07).
 */
public record ServerEvent(
        Instant timestamp,
        String eventType,
        String message,
        String details
) {
    public static ServerEvent of(String eventType, String message, String details) {
        return new ServerEvent(Instant.now(), eventType, message, details);
    }
}
