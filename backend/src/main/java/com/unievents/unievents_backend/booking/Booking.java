package com.unievents.unievents_backend.booking;

import java.time.Instant;
import jakarta.persistence.*;

@Entity
@Table(name = "bookings")
public class Booking {
    public enum Status { CONFIRMED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;
    @Column(name = "seat_id", nullable = false, unique = true, updatable = false)
    private Long seatId;
    @Column(name = "booking_reference", nullable = false, unique = true, length = 64, updatable = false)
    private String bookingReference;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20, updatable = false)
    private Status status = Status.CONFIRMED;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Booking() {}
    public Booking(Long userId, Long eventId, Long seatId, String bookingReference) {
        this.userId = userId;
        this.eventId = eventId;
        this.seatId = seatId;
        this.bookingReference = bookingReference;
    }
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getEventId() { return eventId; }
    public Long getSeatId() { return seatId; }
    public String getBookingReference() { return bookingReference; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
