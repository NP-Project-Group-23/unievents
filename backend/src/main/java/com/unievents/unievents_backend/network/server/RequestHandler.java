package com.unievents.unievents_backend.network.server;

import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;

/**
 * Functional interface for handling inbound TCP protocol requests.
 */
@FunctionalInterface
public interface RequestHandler {

    /**
     * Processes an inbound request envelope in the scope of a connection context.
     *
     * @param request the deserialized request envelope
     * @param context the connection context holding socket and session state
     * @return the response envelope to transmit back to the client
     */
    ResponseEnvelope handle(RequestEnvelope request, ConnectionContext context);
}
