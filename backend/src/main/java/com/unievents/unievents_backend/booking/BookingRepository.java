package com.unievents.unievents_backend.booking;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Page<Booking> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);
    Page<Booking> findByEventIdOrderByCreatedAtDescIdDesc(Long eventId, Pageable pageable);
    Optional<Booking> findByBookingReferenceAndUserId(String reference, Long userId);
    Optional<Booking> findByIdAndUserId(Long id, Long userId);
    boolean existsBySeatId(Long seatId);
}
