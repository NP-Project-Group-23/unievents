-- Existing installations must backfill genuine event records before this version.
-- Fail rather than fabricate events or delete orphan seats/bookings.
ALTER TABLE seats ADD CONSTRAINT fk_seats_event FOREIGN KEY (event_id) REFERENCES events(id);
ALTER TABLE bookings ADD CONSTRAINT fk_bookings_event FOREIGN KEY (event_id) REFERENCES events(id);
