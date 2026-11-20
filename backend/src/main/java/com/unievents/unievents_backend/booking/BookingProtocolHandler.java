package com.unievents.unievents_backend.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.event.EventException;
import com.unievents.unievents_backend.network.protocol.*;
import com.unievents.unievents_backend.network.server.*;
import com.unievents.unievents_backend.seat.*;
import java.util.Locale;
import org.springframework.stereotype.Component;
import static com.unievents.unievents_backend.network.protocol.ErrorCode.*;

@Component
public class BookingProtocolHandler implements RequestHandler {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final BookingService bookings;
    private final SeatService seats;

    public BookingProtocolHandler(BookingService bookings, SeatService seats) {
        this.bookings = bookings;
        this.seats = seats;
    }

    @Override
    public ResponseEnvelope handle(RequestEnvelope request, ConnectionContext context) {
        if (context == null || !context.isAuthenticated()) {
            return ResponseEnvelope.error(request.requestId(), UNAUTHENTICATED, "Authentication required.");
        }
        try {
            JsonNode payload = request.payload();
            if (payload == null || !payload.isObject()) {
                throw new BookingException(BookingException.Code.INVALID_REQUEST, "Payload must be an object.");
            }
            String type = request.type().trim().toUpperCase(Locale.ROOT);
            if (type.equals("EVENT_BOOKINGS") && !"ADMIN".equals(context.role())) {
                return ResponseEnvelope.error(request.requestId(), FORBIDDEN, "Administrator privilege required.");
            }
            ObjectNode data;
            switch (type) {
                case "GET_SEATS" -> {
                    long eventId = positiveLong(payload, "eventId");
                    var page = seats.list(eventId, integer(payload, "page", 0), integer(payload, "size", 20));
                    data = page(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
                    data.put("eventId", eventId);
                    var items = data.putArray("items");
                    page.forEach(seat -> items.add(seat(seat)));
                }
                case "BOOK_SEAT" -> data = booking(bookings.bookSeat(context.userId(),
                    new BookingRequest(positiveLong(payload, "eventId"), positiveLong(payload, "seatId"),
                        optionalText(payload, "idempotencyKey"))));
                case "MY_BOOKINGS" -> {
                    var page = bookings.getMyBookings(context.userId(), integer(payload, "page", 0), integer(payload, "size", 20));
                    data = page(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
                    var items = data.putArray("items");
                    page.forEach(result -> items.add(booking(result)));
                }
                case "GET_BOOKING" -> data = booking(bookings.getMyBooking(context.userId(), positiveLong(payload, "bookingId")));
                case "EVENT_BOOKINGS" -> {
                    long eventId = positiveLong(payload, "eventId");
                    var page = bookings.getEventBookings(eventId, integer(payload, "page", 0), integer(payload, "size", 20));
                    data = page(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
                    data.put("eventId", eventId);
                    var items = data.putArray("items");
                    page.forEach(result -> items.add(booking(result)));
                }
                default -> throw new BookingException(BookingException.Code.INVALID_REQUEST, "Unknown booking operation.");
            }
            ResponseEnvelope response = ResponseEnvelope.success(request.requestId(), data);
            if (JSON.writeValueAsBytes(response).length > FrameCodec.MAX_FRAME_BYTES) {
                return ResponseEnvelope.error(request.requestId(), INVALID_REQUEST, "Response exceeds frame limit; request a smaller page.");
            }
            return response;
        } catch (BookingException ex) {
            return ResponseEnvelope.error(request.requestId(), map(ex.getCode()), ex.getMessage());
        } catch (EventException ex) {
            return ResponseEnvelope.error(request.requestId(), ex.getCode(), ex.getMessage());
        } catch (JsonProcessingException ex) {
            return ResponseEnvelope.error(request.requestId(), INTERNAL_ERROR, "Unable to encode booking response.");
        }
    }

    private static ErrorCode map(BookingException.Code code) {
        return switch (code) {
            case SEAT_UNAVAILABLE -> SEAT_ALREADY_BOOKED;
            default -> ErrorCode.valueOf(code.name());
        };
    }
    private static ObjectNode page(int page, int size, long elements, int pages) {
        return JSON.createObjectNode().put("page", page).put("size", size)
            .put("totalElements", elements).put("totalPages", pages);
    }
    private static ObjectNode seat(SeatResult seat) {
        return JSON.createObjectNode().put("seatId", seat.seatId()).put("eventId", seat.eventId())
            .put("seatNumber", seat.seatNumber()).put("status", seat.status().name());
    }
    private static ObjectNode booking(BookingResult result) {
        return JSON.createObjectNode().put("bookingId", result.bookingId())
            .put("bookingReference", result.bookingReference()).put("eventId", result.eventId())
            .put("seatId", result.seatId()).put("status", result.status().name())
            .put("createdAt", result.createdAt().toString());
    }
    private static ObjectNode booking(EventBookingResult result) {
        return JSON.createObjectNode().put("bookingId", result.bookingId()).put("userId", result.userId())
            .put("bookingReference", result.bookingReference()).put("eventId", result.eventId())
            .put("seatId", result.seatId()).put("status", result.status().name())
            .put("createdAt", result.createdAt().toString());
    }
    private static long positiveLong(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new BookingException(BookingException.Code.INVALID_REQUEST, field + " must be a positive integer.");
        }
        return value.longValue();
    }
    private static int integer(JsonNode payload, String field, int fallback) {
        JsonNode value = payload.get(field);
        if (value == null) return fallback;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new BookingException(BookingException.Code.INVALID_REQUEST, field + " must be an integer.");
        }
        return value.intValue();
    }
    private static String optionalText(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) {
            throw new BookingException(BookingException.Code.INVALID_REQUEST, field + " must be a string.");
        }
        return value.textValue();
    }
}
