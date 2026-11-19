package com.unievents.unievents_backend.ui;

import com.unievents.unievents_backend.UnieventsBackendApplication;
import com.unievents.unievents_backend.network.client.TcpClient;
import java.time.Duration;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** One-process local demo launcher: embedded database, Spring server and JavaFX desktop. */
public final class UniEventsLocalApplication extends Application {
    private ConfigurableApplicationContext spring;
    private TcpClient client;
    private AuthUiCoordinator coordinator;

    @Override
    public void start(Stage stage) throws Exception {
        spring = new SpringApplicationBuilder(UnieventsBackendApplication.class)
            .profiles("local")
            .run();
        client = new TcpClient("127.0.0.1", 9090, Duration.ofSeconds(10), true);
        client.connect();
        coordinator = new AuthUiCoordinator(client);
        stage.setTitle("UniEvents Local Demo");
        stage.setScene(new Scene(coordinator, 1100, 720));
        stage.setMinWidth(850);
        stage.setMinHeight(600);
        stage.show();
    }

    @Override
    public void stop() {
        if (coordinator != null && coordinator.getDashboardView() != null) coordinator.getDashboardView().close();
        if (client != null) client.close();
        if (spring != null) spring.close();
    }

    public static void main(String[] args) { launch(args); }
}
