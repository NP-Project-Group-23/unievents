package com.unievents.unievents_backend.network.server;

import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.FrameCodec;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.InetSocketAddress;
import com.unievents.unievents_backend.network.session.SocketClientHandler;
import com.unievents.unievents_backend.network.executor.ClientExecutionManager;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
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
    public static final int DEFAULT_IDLE_TIMEOUT_MS = 30000;

    private final int requestedPort;
    private String bindAddress = "127.0.0.1";
    private ClientExecutionManager executionManager;
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
            this.executor = new ThreadPoolExecutor(10, 20, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(50), r -> {
                Thread t = new Thread(r, "TcpServer-Worker");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
            this.ownsExecutor = true;
        }
        this.idleTimeoutMs = idleTimeoutMs > 0 ? idleTimeoutMs : DEFAULT_IDLE_TIMEOUT_MS;
    }

    /** Production constructor: all accepted clients use the shared execution manager. */
    public TcpServer(String bindAddress, int port, RequestDispatcher dispatcher,
                     ClientExecutionManager executionManager, int idleTimeoutMs) {
        this(port, dispatcher, executionManager.getMetricsService().getExecutor(), idleTimeoutMs);
        this.bindAddress = java.util.Objects.requireNonNull(bindAddress);
        this.executionManager = java.util.Objects.requireNonNull(executionManager);
    }

    /**
     * Starts listening on the configured TCP port.
     */
    public synchronized void start() throws IOException {
        if (running.get()) {
            log.warn("TcpServer is already running on port {}", getPort());
            return;
        }
        ServerSocket listener = new ServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(bindAddress, requestedPort));
        } catch (IOException | RuntimeException ex) {
            listener.close();
            throw ex;
        }
        serverSocket = listener;
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
                try {
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(idleTimeoutMs);
                } catch (IOException ex) {
                    socket.close();
                    throw ex;
                }
                activeSockets.add(socket);

                try {
                    var handler = new SocketClientHandler(socket, dispatcher, idleTimeoutMs);
                    if (executionManager != null) {
                        activeSockets.remove(socket); // Shared registry owns production sockets.
                        executionManager.submitClient(handler);
                    } else {
                        executor.execute(() -> {
                            try { handler.run(); }
                            finally { activeSockets.remove(socket); }
                        });
                    }
                } catch (RejectedExecutionException ree) {
                    log.warn("Thread pool saturated; rejecting client {}", socket.getRemoteSocketAddress());
                    handleOverloadRejection(socket);
                } catch (RuntimeException ex) {
                    activeSockets.remove(socket);
                    socket.close();
                    log.error("Unable to submit client connection", ex);
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

        if (acceptThread != null && acceptThread != Thread.currentThread()) {
            try { acceptThread.join(2000); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }
        for (Socket s : activeSockets) {
            try { s.close(); } catch (IOException ignored) { }
        }
        activeSockets.clear();
        if (executionManager != null) {
            executionManager.getConnectionManager().closeAll();
        }

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
