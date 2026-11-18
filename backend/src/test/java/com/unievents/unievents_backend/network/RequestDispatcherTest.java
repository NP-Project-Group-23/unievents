package com.unievents.unievents_backend.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import com.unievents.unievents_backend.network.server.ConnectionContext;
import com.unievents.unievents_backend.network.server.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RequestDispatcher Routing and Error Handling Unit Tests")
class RequestDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private RequestDispatcher dispatcher;
    private ConnectionContext dummyContext;

    @BeforeEach
    void setUp() {
        dispatcher = new RequestDispatcher();
        dummyContext = new ConnectionContext("conn-test-1", 42L, "student@uni.edu", "STUDENT", null);
    }

    @Test
    @DisplayName("Verify default PING operation returns PONG response")
    void testPingHandlerReturnsPong() {
        RequestEnvelope pingReq = RequestEnvelope.of("PING", null);
        ResponseEnvelope response = dispatcher.dispatch(pingReq, dummyContext);

        assertNotNull(response);
        assertEquals(pingReq.requestId(), response.requestId());
        assertTrue(response.isSuccess());
        assertNotNull(response.data());
        assertEquals("PONG", response.data().get("message").asText());
        assertTrue(response.data().has("timestamp"));
    }

    @Test
    @DisplayName("Verify default ECHO operation returns matching payload")
    void testEchoHandlerReturnsPayload() {
        ObjectNode echoPayload = MAPPER.createObjectNode().put("testKey", "testValue");
        RequestEnvelope echoReq = RequestEnvelope.of("ECHO", echoPayload);
        ResponseEnvelope response = dispatcher.dispatch(echoReq, dummyContext);

        assertNotNull(response);
        assertEquals(echoReq.requestId(), response.requestId());
        assertTrue(response.isSuccess());
        assertEquals("testValue", response.data().get("testKey").asText());
    }

    @Test
    @DisplayName("Verify custom registered handler execution and context access")
    void testCustomRegisteredHandlerExecution() {
        dispatcher.registerHandler("GET_PROFILE", (req, ctx) -> {
            ObjectNode data = MAPPER.createObjectNode()
                    .put("userId", ctx.userId())
                    .put("email", ctx.email())
                    .put("role", ctx.role());
            return ResponseEnvelope.success(req.requestId(), data);
        });

        RequestEnvelope req = RequestEnvelope.of("GET_PROFILE", null);
        ResponseEnvelope resp = dispatcher.dispatch(req, dummyContext);

        assertNotNull(resp);
        assertTrue(resp.isSuccess());
        assertEquals(42L, resp.data().get("userId").asLong());
        assertEquals("student@uni.edu", resp.data().get("email").asText());
        assertEquals("STUDENT", resp.data().get("role").asText());
    }

    @Test
    @DisplayName("Verify unknown action returns UNKNOWN_ACTION error code")
    void testUnknownActionReturnsError() {
        RequestEnvelope unknownReq = RequestEnvelope.of("NON_EXISTENT_VERB", null);
        ResponseEnvelope response = dispatcher.dispatch(unknownReq, dummyContext);

        assertNotNull(response);
        assertEquals(unknownReq.requestId(), response.requestId());
        assertTrue(response.isError());
        assertEquals(ErrorCode.UNKNOWN_ACTION.name(), response.errorCode());
        assertTrue(response.errorMessage().contains("NON_EXISTENT_VERB"));
    }

    @Test
    @DisplayName("Verify null or blank type returns INVALID_REQUEST error code")
    void testMissingOrBlankTypeReturnsInvalidRequest() {
        RequestEnvelope nullTypeReq = new RequestEnvelope("req-1", null, null, null);
        ResponseEnvelope resp1 = dispatcher.dispatch(nullTypeReq, dummyContext);

        assertTrue(resp1.isError());
        assertEquals(ErrorCode.INVALID_REQUEST.name(), resp1.errorCode());

        RequestEnvelope blankTypeReq = new RequestEnvelope("req-2", "   ", null, null);
        ResponseEnvelope resp2 = dispatcher.dispatch(blankTypeReq, dummyContext);

        assertTrue(resp2.isError());
        assertEquals(ErrorCode.INVALID_REQUEST.name(), resp2.errorCode());

        ResponseEnvelope resp3 = dispatcher.dispatch(null, dummyContext);
        assertTrue(resp3.isError());
        assertEquals(ErrorCode.INVALID_REQUEST.name(), resp3.errorCode());
    }

    @Test
    @DisplayName("Verify handler exception is caught and returns INTERNAL_ERROR")
    void testHandlerExceptionReturnsInternalError() {
        dispatcher.registerHandler("FAILING_OP", (req, ctx) -> {
            throw new RuntimeException("Simulated database failure");
        });

        RequestEnvelope req = RequestEnvelope.of("FAILING_OP", null);
        ResponseEnvelope resp = dispatcher.dispatch(req, dummyContext);

        assertNotNull(resp);
        assertTrue(resp.isError());
        assertEquals(ErrorCode.INTERNAL_ERROR.name(), resp.errorCode());
        assertTrue(resp.errorMessage().contains("Simulated database failure"));
    }

    @Test
    @DisplayName("Verify case-insensitivity of operation type dispatching")
    void testCaseInsensitiveDispatching() {
        dispatcher.registerHandler("custom_action", (req, ctx) ->
                ResponseEnvelope.success(req.requestId(), MAPPER.createObjectNode().put("matched", true))
        );

        RequestEnvelope req1 = RequestEnvelope.of("CUSTOM_ACTION", null);
        RequestEnvelope req2 = RequestEnvelope.of("custom_action", null);
        RequestEnvelope req3 = RequestEnvelope.of("CuStOm_AcTiOn", null);

        assertTrue(dispatcher.dispatch(req1, dummyContext).isSuccess());
        assertTrue(dispatcher.dispatch(req2, dummyContext).isSuccess());
        assertTrue(dispatcher.dispatch(req3, dummyContext).isSuccess());
    }
}
