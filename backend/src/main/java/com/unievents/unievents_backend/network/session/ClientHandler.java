package com.unievents.unievents_backend.network.session;

import com.unievents.unievents_backend.network.executor.ServerOverloadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Interface representing a per-connection client handler task (Member 02 deliverable B05).
 * Submitted to Member 05's bounded {@code ServerThreadPoolExecutor} via {@code ClientExecutionManager}.
 */
public interface ClientHandler extends Runnable, AutoCloseable {

    Logger log = LoggerFactory.getLogger(ClientHandler.class);

    /**
     * Returns the underlying client connection abstraction.
     */
    ClientConnection getConnection();

    /**
     * Main request-processing loop for this client session.
     */
    @Override
    void run();

    /**
     * Cleans up socket, stream, and session resources upon client disconnection.
     */
    @Override
    void close() throws Exception;

    /**
     * Invoked when the worker pool and task queue are saturated and reject this client.
     * Writes a structured backpressure notification to the client and terminates the connection cleanly.
     *
     * @param ex the overload rejection exception containing queue and worker statistics
     */
    default void onOverload(ServerOverloadException ex) {
        log.warn("Notifying client '{}' of server overload rejection: {}",
                getConnection().getConnectionId(), ex.getMessage());
        try {
            // If the connection has a socket, attempt to send a structured overload response
            // before closing so the client receives an explicit error code.
            close();
        } catch (Exception e) {
            log.debug("Error while closing overloaded client connection: {}", e.getMessage());
        }
    }
}
