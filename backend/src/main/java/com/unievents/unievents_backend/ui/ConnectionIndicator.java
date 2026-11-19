package com.unievents.unievents_backend.ui;

import com.unievents.unievents_backend.network.client.ConnectionListener;
import com.unievents.unievents_backend.network.client.ConnectionState;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.Objects;

/**
 * Visual JavaFX status badge displaying real-time TCP connection status
 * with dynamic color indicator (Green, Amber, Red) and descriptive text label.
 * Listens to {@link com.unievents.unievents_backend.network.client.TcpClient} state changes
 * and safely updates UI elements on the JavaFX Application Thread.
 */
public class ConnectionIndicator extends HBox implements ConnectionListener {

    public static final String COLOR_CONNECTED = "#22C55E";   // Emerald Green
    public static final String COLOR_CONNECTING = "#F59E0B";  // Amber
    public static final String COLOR_DISCONNECTED = "#EF4444"; // Red

    public static final String TEXT_CONNECTED = "Connected to Server";
    public static final String TEXT_CONNECTING = "Connecting...";
    public static final String TEXT_RECONNECTING = "Reconnecting...";
    public static final String TEXT_DISCONNECTED = "Disconnected";

    private final Circle statusDot;
    private final Label statusLabel;
    private volatile ConnectionState currentState = ConnectionState.DISCONNECTED;

    public ConnectionIndicator() {
        this(ConnectionState.DISCONNECTED);
    }

    public ConnectionIndicator(ConnectionState initialState) {
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8.0);
        setPadding(new Insets(4, 10, 4, 10));
        setStyle("-fx-background-color: #F8FAFC; -fx-background-radius: 12px; -fx-border-color: #E2E8F0; -fx-border-radius: 12px; -fx-border-width: 1px;");

        this.statusDot = new Circle(5.0);
        this.statusDot.setId("connection-status-dot");

        this.statusLabel = new Label();
        this.statusLabel.setId("connection-status-label");
        this.statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #475569; -fx-font-weight: bold;");

        getChildren().addAll(statusDot, statusLabel);

        updateState(initialState != null ? initialState : ConnectionState.DISCONNECTED);
    }

    @Override
    public void onConnectionStateChanged(ConnectionState oldState, ConnectionState newState) {
        updateState(newState);
    }

    /**
     * Updates the badge color and descriptive text according to the connection state.
     * Ensures UI changes are scheduled safely on the JavaFX application thread.
     */
    public void updateState(ConnectionState state) {
        this.currentState = Objects.requireNonNullElse(state, ConnectionState.DISCONNECTED);

        FxThreadUtils.runOnFxThread(() -> {
            switch (this.currentState) {
                case CONNECTED -> {
                    statusDot.setFill(Color.web(COLOR_CONNECTED));
                    statusLabel.setText(TEXT_CONNECTED);
                }
                case CONNECTING -> {
                    statusDot.setFill(Color.web(COLOR_CONNECTING));
                    statusLabel.setText(TEXT_CONNECTING);
                }
                case RECONNECTING -> {
                    statusDot.setFill(Color.web(COLOR_CONNECTING));
                    statusLabel.setText(TEXT_RECONNECTING);
                }
                case DISCONNECTED -> {
                    statusDot.setFill(Color.web(COLOR_DISCONNECTED));
                    statusLabel.setText(TEXT_DISCONNECTED);
                }
            }
        });
    }

    public ConnectionState getCurrentState() {
        return currentState;
    }

    public Circle getStatusDot() {
        return statusDot;
    }

    public Label getStatusLabel() {
        return statusLabel;
    }

    public String getStatusText() {
        return statusLabel.getText();
    }
}
