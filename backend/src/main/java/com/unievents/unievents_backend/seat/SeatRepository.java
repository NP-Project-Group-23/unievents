package com.unievents.unievents_backend.seat;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    void deleteByEventId(Long eventId);
    Page<Seat> findByEventIdOrderByIdAsc(Long eventId, Pageable pageable);
    List<Seat> findByEventIdOrderBySeatNumberAsc(Long eventId);
    List<Seat> findByEventIdAndStatusOrderBySeatNumberAsc(Long eventId, Seat.Status status);
    Optional<Seat> findByIdAndEventId(Long id, Long eventId);

    /** Returns 1 for the winner, 0 when unavailable or outside the event.
     * The caller must persist the booking in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Seat s set s.status = com.unievents.unievents_backend.seat.Seat.Status.BOOKED "
        + "where s.id = :seatId and s.eventId = :eventId "
        + "and s.status = com.unievents.unievents_backend.seat.Seat.Status.AVAILABLE")
    int reserveIfAvailable(@Param("seatId") Long seatId, @Param("eventId") Long eventId);
}
