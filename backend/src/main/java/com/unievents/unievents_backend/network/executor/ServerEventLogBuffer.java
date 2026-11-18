package com.unievents.unievents_backend.network.executor;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe bounded in-memory circular buffer for genuine server operational events.
 * Provides authentic logs for the monitoring screen (F07) without fabricated values.
 */
@Component
public class ServerEventLogBuffer {

    private static final int DEFAULT_CAPACITY = 100;

    private final int capacity;
    private final ConcurrentLinkedDeque<ServerEvent> buffer = new ConcurrentLinkedDeque<>();

    public ServerEventLogBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public ServerEventLogBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Records a genuine server lifecycle or concurrency event.
     */
    public void record(String eventType, String message, String details) {
        buffer.addFirst(ServerEvent.of(eventType, message, details));
        while (buffer.size() > capacity) {
            buffer.pollLast();
        }
    }

    /**
     * Retrieves the most recent events up to {@code limit}, newest first.
     */
    public List<ServerEvent> getRecentEvents(int limit) {
        int max = Math.max(1, limit);
        List<ServerEvent> result = new ArrayList<>();
        int count = 0;
        for (ServerEvent event : buffer) {
            if (count++ >= max) {
                break;
            }
            result.add(event);
        }
        return result;
    }

    /**
     * Clears all recorded events.
     */
    public void clear() {
        buffer.clear();
    }

    public int size() {
        return buffer.size();
    }

    public int getCapacity() {
        return capacity;
    }
}
