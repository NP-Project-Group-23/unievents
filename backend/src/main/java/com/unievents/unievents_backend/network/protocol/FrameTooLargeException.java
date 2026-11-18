package com.unievents.unievents_backend.network.protocol;

import java.io.IOException;

/**
 * Exception thrown when an incoming or outgoing frame exceeds the bounded
 * protocol threshold (64 KB).
 */
public class FrameTooLargeException extends IOException {

    public FrameTooLargeException(String message) {
        super(message);
    }

    public FrameTooLargeException(String message, Throwable cause) {
        super(message, cause);
    }
}
