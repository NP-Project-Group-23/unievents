package com.unievents.unievents_backend.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import com.unievents.unievents_backend.network.server.ConnectionContext;
import com.unievents.unievents_backend.network.server.RequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionProtocolHandler implements RequestHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(SubscriptionProtocolHandler.class);
    private final UdpSubscriptionManager subscriptions;

    public SubscriptionProtocolHandler(UdpSubscriptionManager subscriptions) {
        this.subscriptions = subscriptions;
    }

    @Override
    public ResponseEnvelope handle(RequestEnvelope request, ConnectionContext context) {
        try {
            JsonNode payload = request.payload();
            if (payload == null || !payload.isObject()) return error(request, ErrorCode.INVALID_REQUEST, "Payload must be an object.");
            return switch (request.type().strip().toUpperCase(java.util.Locale.ROOT)) {
                case "SUBSCRIBE_EVENT" -> subscribe(request, context, payload);
                case "UNSUBSCRIBE_EVENT" -> unsubscribe(request, payload);
                default -> error(request, ErrorCode.UNKNOWN_ACTION, "Unsupported subscription operation.");
            };
        } catch (SubscriptionException ex) {
            return error(request, ErrorCode.valueOf(ex.code()), ex.getMessage());
        } catch (IllegalArgumentException ex) {
            return error(request, ErrorCode.INVALID_REQUEST, ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Subscription request {} failed", request.requestId(), ex);
            return error(request, ErrorCode.INTERNAL_ERROR, "Internal error processing request.");
        }
    }

    private ResponseEnvelope subscribe(RequestEnvelope request, ConnectionContext context, JsonNode payload) {
        long eventId = positiveLong(payload, "eventId");
        int port = integer(payload, "udpPort");
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("udpPort must be between 1 and 65535.");
        if (context.socket() == null || context.socket().getInetAddress() == null)
            throw new IllegalArgumentException("TCP peer address is unavailable.");
        var result = subscriptions.subscribe(resolveToken(request), context.userId(), eventId,
            context.socket().getInetAddress(), port);
        var data = JSON.createObjectNode();
        data.put("subscriptionId", result.subscriptionId());
        data.put("eventId", result.eventId());
        data.put("expiresAt", result.expiresAt().toString());
        data.put("streamId", result.streamId());
        return ResponseEnvelope.success(request.requestId(), data);
    }

    private ResponseEnvelope unsubscribe(RequestEnvelope request, JsonNode payload) {
        JsonNode value = payload.get("subscriptionId");
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException("subscriptionId is required.");
        boolean removed = subscriptions.unsubscribe(resolveToken(request), value.asText().strip());
        var data = JSON.createObjectNode();
        data.put("subscriptionId", value.asText().strip());
        data.put("removed", removed);
        return ResponseEnvelope.success(request.requestId(), data);
    }

    private static long positiveLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0)
            throw new IllegalArgumentException(field + " must be a positive integer.");
        return value.longValue();
    }

    private static int integer(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
            throw new IllegalArgumentException(field + " must be an integer.");
        return value.intValue();
    }

    private static ResponseEnvelope error(RequestEnvelope request, ErrorCode code, String message) {
        return ResponseEnvelope.error(request.requestId(), code, message);
    }

    private static String resolveToken(RequestEnvelope request) {
        if (request.token() != null && !request.token().isBlank()) return request.token().strip();
        JsonNode payloadToken = request.payload().get("token");
        return payloadToken != null && payloadToken.isTextual() ? payloadToken.asText().strip() : null;
    }
}
