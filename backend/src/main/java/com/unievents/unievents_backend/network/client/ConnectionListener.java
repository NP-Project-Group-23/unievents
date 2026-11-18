package com.unievents.unievents_backend.network.client;

/**
 * Listener interface notified when {@link TcpClient} connection state transitions.
 */
@FunctionalInterface
public interface ConnectionListener {

    /**
     * Invoked when the connection state changes.
     *
     * @param oldState previous state
     * @param newState newly transitioned state
     */
    void onConnectionStateChanged(ConnectionState oldState, ConnectionState newState);
}
