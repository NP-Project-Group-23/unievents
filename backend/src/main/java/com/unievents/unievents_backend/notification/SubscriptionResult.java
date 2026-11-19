package com.unievents.unievents_backend.notification;

import java.time.Instant;

public record SubscriptionResult(String subscriptionId, Long eventId, Instant expiresAt, String streamId) {}
