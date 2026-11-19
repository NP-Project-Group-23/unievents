package com.unievents.unievents_backend.event;

import com.unievents.unievents_backend.network.protocol.ErrorCode;

public class EventException extends RuntimeException {
    private final ErrorCode code;
    public EventException(ErrorCode code, String message) { super(message); this.code = code; }
    public ErrorCode getCode() { return code; }
}
