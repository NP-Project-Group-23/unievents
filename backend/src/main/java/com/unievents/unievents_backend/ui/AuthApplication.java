package com.unievents.unievents_backend.ui;

import com.unievents.unievents_backend.network.client.TcpClient;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.concurrent.CompletableFuture;

/**
 * Standalone JavaFX launcher for Member 01 Authentication Views (F01).
 * Allows developers and evaluators to preview and test LoginView, RegisterView,
 * and ConnectionIndicator directly against a running TCP server.
 */
public class AuthApplication extends Application {

    private TcpClient tcpClient;

    @Override
    public void start(Stage primaryStage) {
        int port = Integer.getInteger("server.tcp.port", 8080);
        String host = System.getProperty("server.tcp.host", "127.0.0.1");

        this.tcpClient = new TcpClient(host, port);

        AuthUiCoordinator coordinator = new AuthUiCoordinator(tcpClient);
        coordinator.setOnAuthenticationComplete(session -> {
            System.out.println(">>> User Authenticated Successfully!");
            System.out.println(">>> User ID: " + session.userId());
            System.out.println(">>> Name: " + session.name());
            System.out.println(">>> Role: " + session.role());
            System.out.println(">>> Session Token: " + session.token());
        });

        // Background initial connection attempt
        CompletableFuture.runAsync(() -> {
            try {
                tcpClient.connect();
            } catch (Exception e) {
                System.out.println(">>> Initial background connection failed: " + e.getMessage());
            }
        });

        Scene scene = new Scene(coordinator, 460, 620);
        primaryStage.setTitle("UniTicket - Authentication (Member 01)");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(420);
        primaryStage.setMinHeight(560);
        primaryStage.setOnCloseRequest(e -> {
            if (tcpClient != null) {
                tcpClient.disconnect();
            }
        });
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
