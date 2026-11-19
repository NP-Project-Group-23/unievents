package com.unievents.unievents_backend.network.client;

import java.time.Instant;

public record SeatChangeNotification(String subscriptionId, String streamId, Long eventId,
                                     Long seatId, String seatStatus, long sequence, Instant timestamp) {}
