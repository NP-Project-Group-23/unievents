package com.unievents.unievents_backend.ui;

import com.unievents.unievents_backend.network.client.TcpClient;
import java.time.Duration;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Standalone JavaFX client. Configure TCP_HOST and TCP_PORT before launching. */
public final class UniEventsDesktopApplication extends Application {
    private TcpClient client;
    private AuthUiCoordinator coordinator;

    @Override
    public void start(Stage stage) throws Exception {
        String host = value("TCP_HOST", "127.0.0.1");
        int port = Integer.parseInt(value("TCP_PORT", "9090"));
        client = new TcpClient(host, port, Duration.ofSeconds(10), true);
        client.connect();
        coordinator = new AuthUiCoordinator(client);
        stage.setTitle("UniEvents Desktop");
        stage.setScene(new Scene(coordinator, 1100, 720));
        stage.setMinWidth(850);
        stage.setMinHeight(600);
        stage.show();
    }

    @Override
    public void stop() {
        if (coordinator != null && coordinator.getDashboardView() != null) coordinator.getDashboardView().close();
        if (client != null) client.close();
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    public static void main(String[] args) { launch(args); }
}
