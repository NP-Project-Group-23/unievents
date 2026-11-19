package com.unievents.unievents_backend.seat;

public record SeatResult(Long seatId, Long eventId, String seatNumber, Seat.Status status) {
    public static SeatResult from(Seat seat) {
        return new SeatResult(seat.getId(), seat.getEventId(), seat.getSeatNumber(), seat.getStatus());
    }
}
