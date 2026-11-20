ALTER TABLE bookings ADD COLUMN idempotency_key VARCHAR(64);

ALTER TABLE bookings ADD CONSTRAINT uq_bookings_user_idempotency
    UNIQUE (user_id, idempotency_key);
