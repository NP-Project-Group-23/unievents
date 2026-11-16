package com.unievents.unievents_backend.seat;

import jakarta.persistence.*;

@Entity
@Table(name = "seats", uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "seat_number"}))
public class Seat {
    public enum Status { AVAILABLE, BOOKED }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;
    @Column(name = "seat_number", nullable = false, length = 30, updatable = false)
    private String seatNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Status status = Status.AVAILABLE;

    protected Seat() {}
    public Seat(Long eventId, String seatNumber) {
        this.eventId = eventId;
        this.seatNumber = seatNumber;
    }
    public Long getId() { return id; }
    public Long getEventId() { return eventId; }
    public String getSeatNumber() { return seatNumber; }
    public Status getStatus() { return status; }
}
