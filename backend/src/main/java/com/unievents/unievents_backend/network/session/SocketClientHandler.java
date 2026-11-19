package com.unievents.unievents_backend.network.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unievents.unievents_backend.network.protocol.*;
import com.unievents.unievents_backend.network.server.*;
import com.unievents.unievents_backend.network.executor.ServerOverloadException;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Processes one socket on a worker; the execution manager tracks its lifetime. */
public final class SocketClientHandler implements ClientHandler {
    private static final Logger log = LoggerFactory.getLogger(SocketClientHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Socket socket;
    private final ClientConnection connection;
    private final RequestDispatcher dispatcher;
    private final int idleTimeoutMs;

    public SocketClientHandler(Socket socket, RequestDispatcher dispatcher, int idleTimeoutMs) {
        this.socket = socket;
        this.connection = ClientConnection.fromSocket(socket);
        this.dispatcher = dispatcher;
        this.idleTimeoutMs = idleTimeoutMs;
    }

    @Override public ClientConnection getConnection() { return connection; }
    @Override public void close() throws IOException { connection.close(); }

    @Override
    public void onOverload(ServerOverloadException ex) {
        try {
            var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            FrameCodec.writeEnvelope(writer, ResponseEnvelope.error(UUID.randomUUID().toString(),
                ErrorCode.SERVER_OVERLOAD, "Server is busy. Reconnect later."));
        } catch (IOException ignored) {
            // Peer may have disconnected before admission completed.
        } finally {
            try { close(); } catch (IOException ignored) { }
        }
    }

    @Override
    public void run() {
        String connectionId = connection.getConnectionId();
        ConnectionContext context = new ConnectionContext(connectionId, null, null, null, socket);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            while (connection.isActive() && !socket.isClosed()) {
                String frame;
                try {
                    frame = FrameCodec.readFrame(reader);
                } catch (SocketTimeoutException ste) {
                    log.info("Connection {} timed out after {} ms idle", connectionId, idleTimeoutMs);
                    break;
                } catch (FrameTooLargeException ftle) {
                    log.warn("Frame too large on connection {}: {}", connectionId, ftle.getMessage());
                    ResponseEnvelope err = ResponseEnvelope.error(
                            UUID.randomUUID().toString(),
                            ErrorCode.FRAME_TOO_LARGE,
                            ftle.getMessage()
                    );
                    try {
                        FrameCodec.writeEnvelope(writer, err);
                    } catch (IOException ignored) {}
                    break; // Close socket after framing limit violation
                } catch (IOException ioe) {
                    log.debug("I/O exception reading from client {}: {}", connectionId, ioe.getMessage());
                    break;
                }

                if (frame == null) {
                    // Client disconnected cleanly (EOF)
                    log.debug("Client {} disconnected (EOF)", connectionId);
                    break;
                }

                RequestEnvelope request;
                try {
                    request = MAPPER.readValue(frame, RequestEnvelope.class);
                } catch (JsonProcessingException jpe) {
                    log.warn("Malformed JSON on connection {}: {}", connectionId, jpe.getMessage());
                    ResponseEnvelope err = ResponseEnvelope.error(
                            UUID.randomUUID().toString(),
                            ErrorCode.MALFORMED_JSON,
                            "Malformed JSON: " + jpe.getOriginalMessage()
                    );
                    FrameCodec.writeEnvelope(writer, err);
                    continue;
                }

                // The dispatcher validates each request token; socket identity is never cached.
                ResponseEnvelope response = dispatcher.dispatch(request, context);

                FrameCodec.writeEnvelope(writer, response);
            }
        } catch (IOException e) {
            log.debug("Socket closed or error on connection {}: {}", connectionId, e.getMessage());
        } finally {
            try {
                close();
            } catch (IOException ignored) {}
        }
    }

}
