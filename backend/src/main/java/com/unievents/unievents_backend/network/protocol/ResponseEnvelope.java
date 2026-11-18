package com.unievents.unievents_backend.network.protocol;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Standard protocol response envelope returned over TCP sockets.
 *
 * @param requestId    Identifier correlating with the matching {@link RequestEnvelope}
 * @param status       Operation outcome status (SUCCESS or ERROR)
 * @param data         Result payload on success
 * @param errorCode    Machine-readable error code on error
 * @param errorMessage Human-readable description on error
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseEnvelope(
        @JsonProperty("requestId") String requestId,
        @JsonProperty("status") String status,
        @JsonProperty("data") JsonNode data,
        @JsonProperty("errorCode") String errorCode,
        @JsonProperty("errorMessage") String errorMessage
) {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_ERROR = "ERROR";

    public static ResponseEnvelope success(String requestId, JsonNode data) {
        return new ResponseEnvelope(requestId, STATUS_SUCCESS, data, null, null);
    }

    public static ResponseEnvelope error(String requestId, ErrorCode errorCode, String errorMessage) {
        return new ResponseEnvelope(
                requestId,
                STATUS_ERROR,
                null,
                errorCode != null ? errorCode.name() : null,
                errorMessage
        );
    }

    public static ResponseEnvelope error(String requestId, String errorCode, String errorMessage) {
        return new ResponseEnvelope(requestId, STATUS_ERROR, null, errorCode, errorMessage);
    }

    @JsonIgnore
    public boolean isSuccess() {
        return STATUS_SUCCESS.equalsIgnoreCase(status);
    }

    @JsonIgnore
    public boolean isError() {
        return STATUS_ERROR.equalsIgnoreCase(status);
    }
}
