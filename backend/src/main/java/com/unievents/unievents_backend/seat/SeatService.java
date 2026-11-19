package com.unievents.unievents_backend.seat;

import com.unievents.unievents_backend.event.EventRepository;
import com.unievents.unievents_backend.event.EventException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.unievents.unievents_backend.network.protocol.ErrorCode.*;

@Service
@Transactional(readOnly = true)
public class SeatService {
    private final SeatRepository seats;
    private final EventRepository events;

    public SeatService(SeatRepository seats, EventRepository events) {
        this.seats = seats;
        this.events = events;
    }

    public Page<SeatResult> list(Long eventId, int page, int size) {
        if (eventId == null || eventId <= 0 || page < 0 || size < 1 || size > 100) {
            throw new EventException(INVALID_REQUEST, "Use a positive eventId, nonnegative page and size from 1 to 100.");
        }
        if (!events.existsById(eventId)) {
            throw new EventException(EVENT_NOT_FOUND, "Event not found.");
        }
        return seats.findByEventIdOrderByIdAsc(eventId, PageRequest.of(page, size)).map(SeatResult::from);
    }
}
