package com.unievents.unievents_backend.network.server;

import java.net.Socket;

/**
 * Encapsulates the per-connection state and authenticated identity for an active client socket.
 *
 * @param connectionId Unique session or connection identifier
 * @param userId       Authenticated user id (or null if unauthenticated)
 * @param email        Authenticated user email (or null)
 * @param role         User role (e.g. STUDENT, ADMIN)
 * @param socket       Underlying TCP client socket
 */
public record ConnectionContext(
        String connectionId,
        Long userId,
        String email,
        String role,
        Socket socket
) {

    public ConnectionContext withUser(Long userId, String email, String role) {
        return new ConnectionContext(this.connectionId, userId, email, role, this.socket);
    }

    public boolean isAuthenticated() {
        return userId != null;
    }
}
