package com.unievents.unievents_backend.event;

import com.unievents.unievents_backend.seat.*;
import com.unievents.unievents_backend.booking.BookingRepository;
import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.unievents.unievents_backend.network.protocol.ErrorCode.*;

@Service
@Transactional(readOnly = true)
public class EventService {
    private final EventRepository events;
    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final UserRepository users;

    public EventService(EventRepository events, SeatRepository seats, BookingRepository bookings, UserRepository users) {
        this.events = events; this.seats = seats; this.bookings = bookings; this.users = users;
    }

    @Transactional
    public EventResult create(Long actorId, String title, String description, String venue, Instant startsAt, int capacity) {
        requireAdmin(actorId);
        validate(title, description, venue, startsAt);
        if (capacity < 1 || capacity > 1000) throw new EventException(VALIDATION_FAILED, "Capacity must be between 1 and 1000.");
        Event event = events.saveAndFlush(new Event(title.strip(), normalizeDescription(description), venue.strip(), startsAt, capacity, actorId));
        var inventory = new ArrayList<Seat>(capacity);
        for (int i = 0; i < capacity; i++) inventory.add(new Seat(event.getId(), label(i)));
        seats.saveAllAndFlush(inventory);
        return EventResult.from(event);
    }

    @Transactional
    public EventResult update(Long actorId, Long id, String title, String description, String venue, Instant startsAt) {
        requireAdmin(actorId);
        validate(title, description, venue, startsAt);
        Event event = locked(id);
        event.update(title.strip(), normalizeDescription(description), venue.strip(), startsAt);
        return EventResult.from(events.saveAndFlush(event));
    }

    @Transactional
    public void delete(Long actorId, Long id) {
        requireAdmin(actorId);
        Event event = locked(id);
        if (bookings.existsByEventId(id)) throw new EventException(VALIDATION_FAILED, "Events with confirmed bookings cannot be deleted.");
        seats.deleteByEventId(id);
        seats.flush();
        events.delete(event);
        events.flush();
    }

    public EventResult get(Long id) {
        requireId(id);
        return events.findById(id).map(EventResult::from)
            .orElseThrow(() -> new EventException(EVENT_NOT_FOUND, "Event not found."));
    }

    public Page<EventResult> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new EventException(INVALID_REQUEST, "Invalid pagination.");
        return events.findAll(PageRequest.of(page, size, Sort.by("startsAt", "id"))).map(EventResult::from);
    }

    private Event locked(Long id) {
        requireId(id);
        return events.findForMutation(id).orElseThrow(() -> new EventException(EVENT_NOT_FOUND, "Event not found."));
    }
    private void requireAdmin(Long id) {
        if (id == null || id <= 0) throw new EventException(UNAUTHENTICATED, "Authentication required.");
        if (users.findById(id).filter(user -> user.role() == Role.ADMIN).isEmpty())
            throw new EventException(FORBIDDEN, "Administrator privilege required.");
    }
    private static void requireId(Long id) {
        if (id == null || id <= 0) throw new EventException(INVALID_REQUEST, "Event ID must be positive.");
    }
    private static void validate(String title, String description, String venue, Instant startsAt) {
        if (title == null || title.isBlank() || title.strip().length() > 150 || venue == null || venue.isBlank()
            || venue.strip().length() > 200 || normalizeDescription(description).length() > 2000
            || startsAt == null || !startsAt.isAfter(Instant.now()))
            throw new EventException(VALIDATION_FAILED, "Provide a title, venue, future start time and valid field lengths.");
    }
    private static String normalizeDescription(String value) { return value == null ? "" : value.strip(); }
    private static String label(int index) {
        int row = index / 8 + 1;
        StringBuilder label = new StringBuilder();
        while (row > 0) { row--; label.insert(0, (char) ('A' + row % 26)); row /= 26; }
        return label.toString() + (index % 8 + 1);
    }
}
