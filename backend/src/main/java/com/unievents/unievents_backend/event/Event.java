package com.unievents.unievents_backend.event;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "events")
public class Event {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 150) private String title;
    @Column(nullable = false, length = 2000) private String description;
    @Column(nullable = false, length = 200) private String venue;
    @Column(name = "starts_at", nullable = false) private Instant startsAt;
    @Column(nullable = false, updatable = false) private int capacity;
    @Column(name = "created_by", nullable = false, updatable = false) private Long createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();

    protected Event() {}
    public Event(String title, String description, String venue, Instant startsAt, int capacity, Long createdBy) {
        update(title, description, venue, startsAt);
        this.capacity = capacity;
        this.createdBy = createdBy;
    }
    public void update(String title, String description, String venue, Instant startsAt) {
        this.title = title;
        this.description = description;
        this.venue = venue;
        this.startsAt = startsAt;
    }
    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getVenue() { return venue; }
    public Instant getStartsAt() { return startsAt; }
    public int getCapacity() { return capacity; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
