package com.unievents.unievents_backend.network.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unievents.unievents_backend.network.protocol.FrameCodec;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * High-performance, thread-safe TCP socket client wrapper with asynchronous request/response
 * multiplexing correlated by {@code requestId}, configurable timeouts, and exponential backoff auto-reconnect.
 */
public class TcpClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_MAX_RECONNECT_ATTEMPTS = 5;
    public static final Duration DEFAULT_INITIAL_RECONNECT_DELAY = Duration.ofMillis(300);
    public static final Duration DEFAULT_MAX_RECONNECT_DELAY = Duration.ofSeconds(5);

    private volatile String host;
    private volatile int port;
    private final Duration defaultRequestTimeout;
    private final boolean autoReconnect;
    private final int maxReconnectAttempts;
    private final Duration initialReconnectDelay;
    private final Duration maxReconnectDelay;

    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final List<ConnectionListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CompletableFuture<ResponseEnvelope>> pendingRequests = new ConcurrentHashMap<>();

    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private volatile BufferedReader reader;
    private final Object writeLock = new Object();

    private Thread readerThread;
    private ScheduledExecutorService scheduler;
    private volatile boolean explicitlyClosed = false;

    public TcpClient(String host, int port) {
        this(host, port, DEFAULT_REQUEST_TIMEOUT, true);
    }

    public TcpClient(String host, int port, Duration defaultRequestTimeout, boolean autoReconnect) {
        this(host, port, defaultRequestTimeout, autoReconnect,
                DEFAULT_MAX_RECONNECT_ATTEMPTS, DEFAULT_INITIAL_RECONNECT_DELAY, DEFAULT_MAX_RECONNECT_DELAY);
    }

    public TcpClient(String host, int port, Duration defaultRequestTimeout, boolean autoReconnect,
                     int maxReconnectAttempts, Duration initialReconnectDelay, Duration maxReconnectDelay) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        this.defaultRequestTimeout = defaultRequestTimeout != null ? defaultRequestTimeout : DEFAULT_REQUEST_TIMEOUT;
        this.autoReconnect = autoReconnect;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.initialReconnectDelay = initialReconnectDelay != null ? initialReconnectDelay : DEFAULT_INITIAL_RECONNECT_DELAY;
        this.maxReconnectDelay = maxReconnectDelay != null ? maxReconnectDelay : DEFAULT_MAX_RECONNECT_DELAY;
        this.scheduler = createScheduler();
    }

    private ScheduledExecutorService createScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "TcpClient-Scheduler-" + host + ":" + port);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Registers a listener for connection lifecycle transitions.
     */
    public void addConnectionListener(ConnectionListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Unregisters a connection listener.
     */
    public void removeConnectionListener(ConnectionListener listener) {
        listeners.remove(listener);
    }

    public ConnectionState getState() {
        return state.get();
    }

    public boolean isConnected() {
        return state.get() == ConnectionState.CONNECTED;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public void setHostAndPort(String host, int port) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
    }

    private void setState(ConnectionState newState) {
        ConnectionState oldState = state.getAndSet(newState);
        if (oldState != newState) {
            log.info("TcpClient [{}:{}] state transitioned from {} to {}", host, port, oldState, newState);
            for (ConnectionListener listener : listeners) {
                try {
                    listener.onConnectionStateChanged(oldState, newState);
                } catch (Exception e) {
                    log.error("Exception in ConnectionListener", e);
                }
            }
        }
    }

    /**
     * Connects synchronously to the remote host.
     */
    public synchronized void connect() throws IOException {
        if (state.get() == ConnectionState.CONNECTED) {
            return;
        }
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = createScheduler();
        }
        explicitlyClosed = false;
        setState(ConnectionState.CONNECTING);
        try {
            doConnect();
        } catch (IOException e) {
            setState(ConnectionState.DISCONNECTED);
            throw e;
        }
    }

    /**
     * Performs physical TCP socket connection and initializes I/O pipelines.
     */
    private void doConnect() throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), (int) defaultRequestTimeout.toMillis());

        this.socket = s;
        this.writer = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

        setState(ConnectionState.CONNECTED);

        readerThread = new Thread(this::readerLoop, "TcpClient-Reader-" + host + ":" + port);
        readerThread.setDaemon(true);
        readerThread.start();
        log.info("TcpClient successfully connected to {}:{}", host, port);
    }

    /**
     * Background response reader loop processing incoming frames from the socket.
     */
    private void readerLoop() {
        log.debug("Reader loop active for {}:{}", host, port);
        while (!explicitlyClosed && state.get() == ConnectionState.CONNECTED) {
            String frame;
            try {
                frame = FrameCodec.readFrame(reader);
            } catch (Exception e) {
                if (!explicitlyClosed) {
                    log.warn("Error reading frame from {}:{}: {}", host, port, e.getMessage());
                }
                break;
            }

            if (frame == null) {
                // EOF reached - server disconnected
                log.info("Server closed connection (EOF) on {}:{}", host, port);
                break;
            }

            try {
                ResponseEnvelope response = MAPPER.readValue(frame, ResponseEnvelope.class);
                if (response != null && response.requestId() != null) {
                    CompletableFuture<ResponseEnvelope> future = pendingRequests.remove(response.requestId());
                    if (future != null) {
                        future.complete(response);
                    } else {
                        log.debug("Received response for unmatched or timed out requestId: {}", response.requestId());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse incoming response JSON: {}", frame, e);
            }
        }

        if (!explicitlyClosed) {
            handleAbruptDisconnection();
        }
    }

    /**
     * Handles unexpected socket disconnection, triggers pending request failure,
     * and schedules exponential backoff reconnect if enabled.
     */
    private void handleAbruptDisconnection() {
        cleanupSocket();

        // Fail pending requests with connection lost exception
        IOException disconnectEx = new IOException("TCP connection lost to " + host + ":" + port);
        for (String reqId : pendingRequests.keySet()) {
            CompletableFuture<ResponseEnvelope> f = pendingRequests.remove(reqId);
            if (f != null) {
                f.completeExceptionally(disconnectEx);
            }
        }

        if (autoReconnect && !explicitlyClosed) {
            setState(ConnectionState.RECONNECTING);
            scheduleReconnect(1, initialReconnectDelay);
        } else {
            setState(ConnectionState.DISCONNECTED);
        }
    }

    /**
     * Schedules a reconnect attempt with exponential backoff.
     */
    private void scheduleReconnect(int attempt, Duration delay) {
        if (explicitlyClosed) {
            setState(ConnectionState.DISCONNECTED);
            return;
        }

        log.info("Scheduling reconnect attempt {} to {}:{} in {} ms", attempt, host, port, delay.toMillis());
        try {
            scheduler.schedule(() -> {
                if (explicitlyClosed) {
                    setState(ConnectionState.DISCONNECTED);
                    return;
                }
                try {
                    log.info("Executing reconnect attempt {} to {}:{}...", attempt, host, port);
                    doConnect();
                    log.info("Reconnect to {}:{} succeeded on attempt {}", host, port, attempt);
                } catch (Exception e) {
                    log.warn("Reconnect attempt {} failed: {}", attempt, e.getMessage());
                    if (attempt < maxReconnectAttempts && !explicitlyClosed) {
                        long nextDelay = Math.min(delay.toMillis() * 2, maxReconnectDelay.toMillis());
                        scheduleReconnect(attempt + 1, Duration.ofMillis(nextDelay));
                    } else {
                        log.error("Max reconnect attempts ({}) reached. Aborting reconnect.", maxReconnectAttempts);
                        setState(ConnectionState.DISCONNECTED);
                    }
                }
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("Failed to schedule reconnect", e);
            setState(ConnectionState.DISCONNECTED);
        }
    }

    /**
     * Sends a request asynchronously with default timeout.
     */
    public CompletableFuture<ResponseEnvelope> sendAsync(RequestEnvelope request) {
        return sendAsync(request, defaultRequestTimeout);
    }

    /**
     * Sends a request asynchronously with specified timeout.
     */
    public CompletableFuture<ResponseEnvelope> sendAsync(RequestEnvelope request, Duration timeout) {
        if (request == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("request must not be null"));
        }
        if (state.get() != ConnectionState.CONNECTED) {
            return CompletableFuture.failedFuture(new IOException("Client is not connected. Current state: " + state.get()));
        }

        Duration effectiveTimeout = (timeout != null && !timeout.isNegative() && !timeout.isZero())
                ? timeout
                : defaultRequestTimeout;

        CompletableFuture<ResponseEnvelope> future = new CompletableFuture<>();
        pendingRequests.put(request.requestId(), future);

        ScheduledFuture<?> timeoutTask = scheduler.schedule(() -> {
            CompletableFuture<ResponseEnvelope> f = pendingRequests.remove(request.requestId());
            if (f != null && !f.isDone()) {
                f.completeExceptionally(new TimeoutException(
                        "Request " + request.requestId() + " timed out after " + effectiveTimeout.toMillis() + "ms"
                ));
            }
        }, effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);

        future.whenComplete((res, err) -> timeoutTask.cancel(false));

        try {
            String json = MAPPER.writeValueAsString(request);
            synchronized (writeLock) {
                if (writer == null || socket == null || socket.isClosed()) {
                    throw new IOException("Socket is closed");
                }
                FrameCodec.writeFrame(writer, json);
            }
        } catch (Exception e) {
            pendingRequests.remove(request.requestId());
            timeoutTask.cancel(false);
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * Sends a request synchronously using default timeout.
     */
    public ResponseEnvelope sendSync(RequestEnvelope request) throws Exception {
        return sendSync(request, defaultRequestTimeout);
    }

    /**
     * Sends a request synchronously using specified timeout.
     */
    public ResponseEnvelope sendSync(RequestEnvelope request, Duration timeout) throws Exception {
        Duration effectiveTimeout = (timeout != null && !timeout.isNegative() && !timeout.isZero())
                ? timeout
                : defaultRequestTimeout;
        try {
            return sendAsync(request, effectiveTimeout).get(effectiveTimeout.toMillis() + 500, TimeUnit.MILLISECONDS);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    private void cleanupSocket() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {}
            writer = null;
        }
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException ignored) {}
            reader = null;
        }
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {}
            socket = null;
        }
    }

    /**
     * Disconnects the socket without shutting down the client scheduler.
     */
    public synchronized void disconnect() {
        explicitlyClosed = true;
        setState(ConnectionState.DISCONNECTED);
        cleanupSocket();

        IOException ex = new IOException("Client disconnected by user");
        for (String reqId : pendingRequests.keySet()) {
            CompletableFuture<ResponseEnvelope> f = pendingRequests.remove(reqId);
            if (f != null) {
                f.completeExceptionally(ex);
            }
        }

        if (readerThread != null) {
            readerThread.interrupt();
        }
    }

    /**
     * Reconnects to a potentially new host/port synchronously.
     */
    public synchronized void reconnectTo(String host, int port) throws IOException {
        disconnect();
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        connect();
    }

    @Override
    public synchronized void close() {
        disconnect();
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
