package com.unievents.unievents_backend.network.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Encodes and decodes newline-delimited JSON (NDJSON) protocol frames over I/O streams.
 * Enforces a strict 64 KB (65,536 bytes) frame boundary to guard against buffer exhaustion.
 */
public final class FrameCodec {

    public static final int MAX_FRAME_BYTES = 65536;
    private static final ObjectMapper DEFAULT_MAPPER = new ObjectMapper();

    private FrameCodec() {
        // Utility class
    }

    /**
     * Reads the next newline-delimited frame from the given reader.
     * Skips empty or whitespace-only lines.
     * Throws {@link FrameTooLargeException} if the frame exceeds {@value #MAX_FRAME_BYTES} UTF-8 bytes.
     *
     * @param reader the buffered character reader
     * @return the trimmed frame string, or {@code null} on end-of-stream
     * @throws IOException if an I/O error occurs or the frame exceeds the size limit
     */
    public static String readFrame(BufferedReader reader) throws IOException {
        if (reader == null) {
            throw new IllegalArgumentException("reader must not be null");
        }

        StringBuilder sb = new StringBuilder();
        int byteCount = 0;
        int c;

        while ((c = reader.read()) != -1) {
            if (c == '\n') {
                String line = sb.toString();
                if (line.endsWith("\r")) {
                    line = line.substring(0, line.length() - 1);
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    // Skip empty/whitespace lines safely
                    sb.setLength(0);
                    byteCount = 0;
                    continue;
                }
                return trimmed;
            }

            sb.append((char) c);

            // Compute UTF-8 byte weight of the incoming character
            int charBytes;
            if (c <= 0x7F) {
                charBytes = 1;
            } else if (c <= 0x7FF) {
                charBytes = 2;
            } else if (Character.isSurrogate((char) c)) {
                charBytes = 2; // half of a 4-byte surrogate pair
            } else {
                charBytes = 3;
            }

            byteCount += charBytes;
            if (byteCount > MAX_FRAME_BYTES) {
                throw new FrameTooLargeException("Frame exceeded maximum allowed size of " + MAX_FRAME_BYTES + " bytes");
            }
        }

        // Handle stream ending without a trailing newline
        if (sb.length() == 0) {
            return null;
        }

        String line = sb.toString();
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        String trimmed = line.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Writes a JSON payload as a newline-delimited frame to the given writer and flushes it.
     * Throws {@link FrameTooLargeException} if the frame exceeds {@value #MAX_FRAME_BYTES} UTF-8 bytes.
     *
     * @param writer the buffered character writer
     * @param json   the JSON string to write
     * @throws IOException if an I/O error occurs or the frame exceeds the size limit
     */
    public static void writeFrame(BufferedWriter writer, String json) throws IOException {
        if (writer == null || json == null) {
            throw new IllegalArgumentException("writer and json must not be null");
        }

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FRAME_BYTES) {
            throw new FrameTooLargeException("Frame exceeded maximum allowed size of " + MAX_FRAME_BYTES + " bytes");
        }

        writer.write(json);
        writer.write("\n");
        writer.flush();
    }

    /**
     * Serializes an object to JSON and writes it as a newline-delimited frame.
     */
    public static void writeEnvelope(BufferedWriter writer, Object envelope) throws IOException {
        if (envelope == null) {
            throw new IllegalArgumentException("envelope must not be null");
        }
        String json = DEFAULT_MAPPER.writeValueAsString(envelope);
        writeFrame(writer, json);
    }

    /**
     * Reads the next frame and deserializes it into the requested class.
     *
     * @return the deserialized object, or {@code null} on end-of-stream
     */
    public static <T> T readEnvelope(BufferedReader reader, Class<T> clazz) throws IOException {
        String frame = readFrame(reader);
        if (frame == null) {
            return null;
        }
        return DEFAULT_MAPPER.readValue(frame, clazz);
    }
}
