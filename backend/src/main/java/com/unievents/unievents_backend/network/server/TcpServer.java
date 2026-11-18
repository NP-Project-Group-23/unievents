package com.unievents.unievents_backend.network.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.FrameCodec;
import com.unievents.unievents_backend.network.protocol.FrameTooLargeException;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Multi-threaded TCP server listening on a configurable port.
 * Accepts incoming client sockets, tunes TCP options, handles idle timeouts,
 * and routes framed protocol requests through the {@link RequestDispatcher}.
 */
public class TcpServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TcpServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int DEFAULT_IDLE_TIMEOUT_MS = 30000;

    private final int requestedPort;
    private final RequestDispatcher dispatcher;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final int idleTimeoutMs;

    private volatile ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();

    public TcpServer(int port, RequestDispatcher dispatcher) {
        this(port, dispatcher, null, DEFAULT_IDLE_TIMEOUT_MS);
    }

    public TcpServer(int port, RequestDispatcher dispatcher, ExecutorService executor) {
        this(port, dispatcher, executor, DEFAULT_IDLE_TIMEOUT_MS);
    }

    public TcpServer(int port, RequestDispatcher dispatcher, ExecutorService executor, int idleTimeoutMs) {
        this.requestedPort = port;
        this.dispatcher = dispatcher != null ? dispatcher : new RequestDispatcher();
        if (executor != null) {
            this.executor = executor;
            this.ownsExecutor = false;
        } else {
            this.executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "TcpServer-Worker");
                t.setDaemon(true);
                return t;
            });
            this.ownsExecutor = true;
        }
        this.idleTimeoutMs = idleTimeoutMs > 0 ? idleTimeoutMs : DEFAULT_IDLE_TIMEOUT_MS;
    }

    /**
     * Starts listening on the configured TCP port.
     */
    public synchronized void start() throws IOException {
        if (running.get()) {
            log.warn("TcpServer is already running on port {}", getPort());
            return;
        }
        serverSocket = new ServerSocket(requestedPort);
        serverSocket.setReuseAddress(true);
        running.set(true);

        acceptThread = new Thread(this::acceptLoop, "TcpServer-Acceptor-" + serverSocket.getLocalPort());
        acceptThread.setDaemon(true);
        acceptThread.start();

        log.info("TcpServer started successfully on port {}", serverSocket.getLocalPort());
    }

    /**
     * Returns the port on which the server is currently bound.
     */
    public int getPort() {
        ServerSocket s = this.serverSocket;
        if (s != null && !s.isClosed()) {
            return s.getLocalPort();
        }
        return requestedPort;
    }

    /**
     * Checks if the server is active and accepting connections.
     */
    public boolean isRunning() {
        return running.get() && serverSocket != null && !serverSocket.isClosed();
    }

    /**
     * Background accept loop awaiting incoming client sockets.
     */
    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(idleTimeoutMs);
                activeSockets.add(socket);

                try {
                    executor.submit(() -> handleClient(socket));
                } catch (RejectedExecutionException ree) {
                    log.warn("Thread pool saturated; rejecting client {}", socket.getRemoteSocketAddress());
                    handleOverloadRejection(socket);
                }
            } catch (SocketException se) {
                if (!running.get()) {
                    // Normal server shutdown
                    break;
                }
                log.error("Socket error in accept loop", se);
            } catch (IOException ioe) {
                if (running.get()) {
                    log.error("I/O error in accept loop", ioe);
                }
            }
        }
    }

    /**
     * Responds to an overloaded connection with SERVER_OVERLOAD before closing.
     */
    private void handleOverloadRejection(Socket socket) {
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            ResponseEnvelope overload = ResponseEnvelope.error(
                    UUID.randomUUID().toString(),
                    ErrorCode.SERVER_OVERLOAD,
                    "Server thread pool is saturated. Please retry later."
            );
            FrameCodec.writeEnvelope(writer, overload);
        } catch (Exception ignored) {
        } finally {
            activeSockets.remove(socket);
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Handles read/dispatch loop for an individual client connection.
     */
    private void handleClient(Socket socket) {
        String connectionId = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        ConnectionContext context = new ConnectionContext(connectionId, null, null, null, socket);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            while (running.get() && !socket.isClosed()) {
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

                ResponseEnvelope response = dispatcher.dispatch(request, context);
                FrameCodec.writeEnvelope(writer, response);
            }
        } catch (IOException e) {
            log.debug("Socket closed or error on connection {}: {}", connectionId, e.getMessage());
        } finally {
            activeSockets.remove(socket);
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Gracefully stops the server, closing all sockets and releasing resources.
     */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("Stopping TcpServer on port {}...", getPort());

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }

        if (acceptThread != null) {
            acceptThread.interrupt();
        }

        for (Socket s : activeSockets) {
            try {
                s.close();
            } catch (IOException ignored) {}
        }
        activeSockets.clear();

        if (ownsExecutor) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("TcpServer stopped successfully");
    }

    @Override
    public void close() {
        stop();
    }
}
