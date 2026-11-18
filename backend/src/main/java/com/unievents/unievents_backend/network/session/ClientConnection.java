package com.unievents.unievents_backend.network.session;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents an active client connection to the UniEvents server.
 * Managed by {@code ClientConnectionManager} and executed by {@code ClientExecutionManager}.
 */
public class ClientConnection implements Closeable {

    private final String connectionId;
    private final String remoteAddress;
    private final Instant connectedAt;
    private final Closeable socketResource;
    private final AtomicBoolean active = new AtomicBoolean(true);

    public ClientConnection(String connectionId, String remoteAddress, Closeable socketResource) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId must not be null");
        this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress must not be null");
        this.socketResource = socketResource;
        this.connectedAt = Instant.now();
    }

    public static ClientConnection fromSocket(Socket socket) {
        Objects.requireNonNull(socket, "socket must not be null");
        String id = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        String remote = socket.getRemoteSocketAddress() != null
                ? socket.getRemoteSocketAddress().toString()
                : "unknown";
        return new ClientConnection(id, remote, socket);
    }

    public static ClientConnection create(String id, String remoteAddress, Closeable resource) {
        return new ClientConnection(id, remoteAddress, resource);
    }

    public String getConnectionId() {
        return connectionId;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public boolean isActive() {
        return active.get();
    }

    @Override
    public void close() throws IOException {
        if (active.compareAndSet(true, false)) {
            if (socketResource != null) {
                socketResource.close();
            }
        }
    }

    @Override
    public String toString() {
        return "ClientConnection{" +
                "id='" + connectionId + '\'' +
                ", remote='" + remoteAddress + '\'' +
                ", active=" + active.get() +
                ", connectedAt=" + connectedAt +
                '}';
    }
}
