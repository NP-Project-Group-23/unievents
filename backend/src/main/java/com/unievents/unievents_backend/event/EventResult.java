package com.unievents.unievents_backend.event;

import java.time.Instant;

public record EventResult(Long eventId, String title, String description, String venue,
                          Instant startsAt, int capacity, Long createdBy, Instant createdAt) {
    public static EventResult from(Event event) {
        return new EventResult(event.getId(), event.getTitle(), event.getDescription(), event.getVenue(),
            event.getStartsAt(), event.getCapacity(), event.getCreatedBy(), event.getCreatedAt());
    }
}
