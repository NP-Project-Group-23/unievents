package com.unievents.unievents_backend.network.client;

/**
 * State lifecycle representing the active network status of {@link TcpClient}.
 */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}
