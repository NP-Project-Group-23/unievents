package com.unievents.unievents_backend.network.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * Standard protocol request envelope exchanged across TCP sockets.
 *
 * @param requestId Unique identifier for multiplexed request/response correlation
 * @param type      Command / operation verb (e.g. PING, LOGIN, REGISTER, BOOK_SEAT)
 * @param token     Optional session authentication token
 * @param payload   Polymorphic JSON payload containing command-specific parameters
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RequestEnvelope(
        @JsonProperty("requestId") String requestId,
        @JsonProperty("type") String type,
        @JsonProperty("token") String token,
        @JsonProperty("payload") JsonNode payload
) {

    public RequestEnvelope {
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
    }

    public static RequestEnvelope of(String type, JsonNode payload) {
        return new RequestEnvelope(UUID.randomUUID().toString(), type, null, payload);
    }

    public static RequestEnvelope of(String type, String token, JsonNode payload) {
        return new RequestEnvelope(UUID.randomUUID().toString(), type, token, payload);
    }
}
