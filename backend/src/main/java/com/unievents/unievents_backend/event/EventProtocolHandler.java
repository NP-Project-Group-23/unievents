package com.unievents.unievents_backend.event;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.network.protocol.*;
import com.unievents.unievents_backend.network.server.*;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import org.springframework.stereotype.Component;
import static com.unievents.unievents_backend.network.protocol.ErrorCode.*;

@Component
public class EventProtocolHandler implements RequestHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final EventService service;
    public EventProtocolHandler(EventService service) { this.service = service; }

    @Override
    public ResponseEnvelope handle(RequestEnvelope request, ConnectionContext context) {
        if (context == null || !context.isAuthenticated())
            return ResponseEnvelope.error(request.requestId(), UNAUTHENTICATED, "Authentication required.");
        String type = request.type().trim().toUpperCase(Locale.ROOT);
        if (!type.equals("LIST_EVENTS") && !type.equals("GET_EVENT") && !"ADMIN".equals(context.role()))
            return ResponseEnvelope.error(request.requestId(), FORBIDDEN, "Administrator privilege required.");
        try {
            JsonNode payload = request.payload();
            if (payload == null || !payload.isObject()) throw new EventException(INVALID_REQUEST, "Payload must be an object.");
            ObjectNode data;
            switch (type) {
                case "CREATE_EVENT" -> data = json(service.create(context.userId(), text(payload,"title",false),
                    text(payload,"description",true), text(payload,"venue",false), date(payload), integer(payload,"capacity",null)));
                case "UPDATE_EVENT" -> {
                    if (payload.has("capacity")) throw new EventException(VALIDATION_FAILED, "Capacity is immutable after creation.");
                    data = json(service.update(context.userId(), id(payload), text(payload,"title",false),
                        text(payload,"description",true), text(payload,"venue",false), date(payload)));
                }
                case "DELETE_EVENT" -> {
                    Long id = id(payload);
                    service.delete(context.userId(), id);
                    data = JSON.createObjectNode().put("eventId",id).put("deleted",true);
                }
                case "GET_EVENT" -> data = json(service.get(id(payload)));
                case "LIST_EVENTS" -> {
                    var page = service.list(integer(payload,"page",0), integer(payload,"size",20));
                    data = JSON.createObjectNode().put("page",page.getNumber()).put("size",page.getSize())
                        .put("totalElements",page.getTotalElements()).put("totalPages",page.getTotalPages());
                    var items = data.putArray("items");
                    page.forEach(event -> items.add(json(event)));
                }
                default -> throw new EventException(UNKNOWN_ACTION, "Unknown event operation.");
            }
            var response = ResponseEnvelope.success(request.requestId(),data);
            if (JSON.writeValueAsBytes(response).length > FrameCodec.MAX_FRAME_BYTES)
                throw new EventException(INVALID_REQUEST, "Response exceeds frame limit; request a smaller page.");
            return response;
        } catch (EventException ex) {
            return ResponseEnvelope.error(request.requestId(),ex.getCode(),ex.getMessage());
        } catch (DateTimeParseException ex) {
            return ResponseEnvelope.error(request.requestId(),VALIDATION_FAILED,"startsAt must be an ISO timestamp with timezone.");
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return ResponseEnvelope.error(request.requestId(),INTERNAL_ERROR,"Unable to encode event response.");
        }
    }

    private static ObjectNode json(EventResult event) {
        return JSON.createObjectNode().put("eventId",event.eventId()).put("title",event.title())
            .put("description",event.description()).put("venue",event.venue()).put("startsAt",event.startsAt().toString())
            .put("capacity",event.capacity()).put("createdBy",event.createdBy()).put("createdAt",event.createdAt().toString());
    }
    private static String text(JsonNode payload, String name, boolean optional) {
        JsonNode value = payload.get(name);
        if (value == null && optional) return "";
        if (value == null || !value.isTextual()) throw new EventException(VALIDATION_FAILED, name + " must be a string.");
        return value.asText();
    }
    private static java.time.Instant date(JsonNode payload) {
        return OffsetDateTime.parse(text(payload,"startsAt",false)).toInstant();
    }
    private static int integer(JsonNode payload, String name, Integer fallback) {
        JsonNode value = payload.get(name);
        if (value == null && fallback != null) return fallback;
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt())
            throw new EventException(INVALID_REQUEST,name + " must be an integer.");
        return value.intValue();
    }
    private static Long id(JsonNode payload) {
        JsonNode value = payload.get("eventId");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0)
            throw new EventException(INVALID_REQUEST,"eventId must be a positive integer.");
        return value.longValue();
    }
}
