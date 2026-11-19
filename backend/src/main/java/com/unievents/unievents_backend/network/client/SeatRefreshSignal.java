package com.unievents.unievents_backend.network.client;

/** Every signal requires a fresh GET_SEATS request; flags explain why recovery is especially important. */
public record SeatRefreshSignal(SeatChangeNotification notification, boolean streamChanged, boolean sequenceGap) {}
