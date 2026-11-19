package com.unievents.unievents_backend.notification;

final class SubscriptionException extends RuntimeException {
    private final String code;
    SubscriptionException(String code, String message) { super(message); this.code = code; }
    String code() { return code; }
}
