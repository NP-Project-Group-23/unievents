package com.unievents.unievents_backend.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.FrameCodec;
import com.unievents.unievents_backend.network.protocol.FrameTooLargeException;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FrameCodec Framing and Protocol Envelope Unit Tests")
class FrameCodecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Verify normal framing write and read round-trip")
    void testNormalFramingEncodeDecode() throws IOException {
        String json = "{\"action\":\"TEST\",\"value\":42}";
        StringWriter sw = new StringWriter();
        BufferedWriter writer = new BufferedWriter(sw);

        FrameCodec.writeFrame(writer, json);
        String framedOutput = sw.toString();

        assertEquals(json + "\n", framedOutput);

        BufferedReader reader = new BufferedReader(new StringReader(framedOutput));
        String decoded = FrameCodec.readFrame(reader);

        assertEquals(json, decoded);
        assertNull(FrameCodec.readFrame(reader), "Subsequent read at EOF must return null");
    }

    @Test
    @DisplayName("Verify RequestEnvelope and ResponseEnvelope serialization and deserialization")
    void testEnvelopeSerializationDeserialization() throws IOException {
        ObjectNode payload = MAPPER.createObjectNode().put("email", "student@university.edu");
        RequestEnvelope request = RequestEnvelope.of("LOGIN", "tok-12345", payload);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8));

        FrameCodec.writeEnvelope(writer, request);

        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(baos.toByteArray()), StandardCharsets.UTF_8));
        RequestEnvelope decodedRequest = FrameCodec.readEnvelope(reader, RequestEnvelope.class);

        assertNotNull(decodedRequest);
        assertEquals(request.requestId(), decodedRequest.requestId());
        assertEquals("LOGIN", decodedRequest.type());
        assertEquals("tok-12345", decodedRequest.token());
        assertEquals("student@university.edu", decodedRequest.payload().get("email").asText());

        // Test ResponseEnvelope
        ObjectNode data = MAPPER.createObjectNode().put("status", "AUTHENTICATED");
        ResponseEnvelope response = ResponseEnvelope.success(request.requestId(), data);

        ByteArrayOutputStream responseBaos = new ByteArrayOutputStream();
        BufferedWriter respWriter = new BufferedWriter(new OutputStreamWriter(responseBaos, StandardCharsets.UTF_8));

        FrameCodec.writeEnvelope(respWriter, response);

        BufferedReader respReader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(responseBaos.toByteArray()), StandardCharsets.UTF_8));
        ResponseEnvelope decodedResp = FrameCodec.readEnvelope(respReader, ResponseEnvelope.class);

        assertNotNull(decodedResp);
        assertEquals(request.requestId(), decodedResp.requestId());
        assertEquals("SUCCESS", decodedResp.status());
        assertTrue(decodedResp.isSuccess());
        assertEquals("AUTHENTICATED", decodedResp.data().get("status").asText());
    }

    @Test
    @DisplayName("Verify multiple coalesced frames in single buffer are decoded sequentially")
    void testMultipleCoalescedFrames() throws IOException {
        String stream = "{\"id\":\"1\"}\n{\"id\":\"2\"}\n{\"id\":\"3\"}\n";
        BufferedReader reader = new BufferedReader(new StringReader(stream));

        assertEquals("{\"id\":\"1\"}", FrameCodec.readFrame(reader));
        assertEquals("{\"id\":\"2\"}", FrameCodec.readFrame(reader));
        assertEquals("{\"id\":\"3\"}", FrameCodec.readFrame(reader));
        assertNull(FrameCodec.readFrame(reader));
    }

    @Test
    @DisplayName("Verify split fragmented frames across chunked reads are reconstructed correctly")
    void testSplitFragmentedFrames() throws IOException {
        // Custom reader simulating slow fragmented delivery
        StringBuilder input = new StringBuilder("{\"part1\":\"hello\",");
        input.append("\"part2\":\"world\"}\n");

        BufferedReader reader = new BufferedReader(new StringReader(input.toString()), 4);
        String frame = FrameCodec.readFrame(reader);

        assertEquals("{\"part1\":\"hello\",\"part2\":\"world\"}", frame);
    }

    @Test
    @DisplayName("Verify oversized frame (>64KB) throws FrameTooLargeException on read")
    void testOversizedFrameRejectionOnRead() {
        // Construct string exceeding 65,536 bytes
        String hugePayload = "A".repeat(65537);
        BufferedReader reader = new BufferedReader(new StringReader(hugePayload + "\n"));

        assertThrows(FrameTooLargeException.class, () -> FrameCodec.readFrame(reader));
    }

    @Test
    @DisplayName("Verify oversized frame (>64KB) throws FrameTooLargeException on write")
    void testOversizedFrameRejectionOnWrite() {
        String hugePayload = "B".repeat(65537);
        BufferedWriter writer = new BufferedWriter(new StringWriter());

        assertThrows(FrameTooLargeException.class, () -> FrameCodec.writeFrame(writer, hugePayload));
    }

    @Test
    @DisplayName("Verify empty and whitespace lines are skipped safely")
    void testEmptyAndWhitespaceLinesSkipped() throws IOException {
        String stream = "\n  \n\r\n\t  \n{\"action\":\"VALID\"}\n\n   \n";
        BufferedReader reader = new BufferedReader(new StringReader(stream));

        String frame = FrameCodec.readFrame(reader);
        assertEquals("{\"action\":\"VALID\"}", frame);
        assertNull(FrameCodec.readFrame(reader), "Trailing whitespace lines must resolve to EOF (null)");
    }

    @Test
    @DisplayName("Verify clean EOF handling on empty stream")
    void testCleanEofHandling() throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(""));
        assertNull(FrameCodec.readFrame(reader));
    }

    @Test
    @DisplayName("Verify Windows CRLF (\\r\\n) is handled cleanly without trailing \\r")
    void testWindowsCrlfHandling() throws IOException {
        String stream = "{\"status\":\"CRLF_TEST\"}\r\n";
        BufferedReader reader = new BufferedReader(new StringReader(stream));

        String frame = FrameCodec.readFrame(reader);
        assertEquals("{\"status\":\"CRLF_TEST\"}", frame);
        assertFalse(frame.endsWith("\r"));
    }

    @Test
    @DisplayName("Verify ResponseEnvelope error helper formats correctly")
    void testResponseEnvelopeErrorHelper() {
        ResponseEnvelope errorResp = ResponseEnvelope.error("req-999", ErrorCode.INVALID_CREDENTIALS, "Bad password");
        assertEquals("req-999", errorResp.requestId());
        assertEquals("ERROR", errorResp.status());
        assertTrue(errorResp.isError());
        assertFalse(errorResp.isSuccess());
        assertEquals(ErrorCode.INVALID_CREDENTIALS.name(), errorResp.errorCode());
        assertEquals("Bad password", errorResp.errorMessage());
    }
}
