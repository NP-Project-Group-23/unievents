-- Pending integration: add event foreign keys once M2 confirms the event schema.
-- This file is not auto-applied: Flyway is not configured in this project.
CREATE TABLE seats (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id BIGINT NOT NULL,
    seat_number VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    CONSTRAINT uq_seats_event_number UNIQUE (event_id, seat_number),
    CONSTRAINT uq_seats_id_event UNIQUE (id, event_id),
    CONSTRAINT ck_seats_status CHECK (status IN ('AVAILABLE', 'BOOKED'))
);

CREATE TABLE bookings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_users(id),
    event_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL UNIQUE,
    booking_reference VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bookings_seat_event FOREIGN KEY (seat_id, event_id) REFERENCES seats(id, event_id),
    CONSTRAINT ck_bookings_status CHECK (status = 'CONFIRMED')
);

CREATE INDEX idx_bookings_user_history ON bookings(user_id, created_at DESC, id DESC);
CREATE INDEX idx_bookings_event_history ON bookings(event_id, created_at DESC, id DESC);
