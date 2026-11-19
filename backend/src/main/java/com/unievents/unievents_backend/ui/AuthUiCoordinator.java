package com.unievents.unievents_backend.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.network.client.TcpClient;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Main UI coordinator managing authentication views (LoginView, RegisterView),
 * binding the live TCP connection indicator, and managing the authenticated session lifecycle.
 * Can be used directly as a JavaFX root layout container.
 */
public class AuthUiCoordinator extends BorderPane {

    public enum ViewType {
        LOGIN,
        REGISTER
    }

    private final TcpClient tcpClient;
    private final ConnectionIndicator connectionIndicator;
    private final LoginView loginView;
    private final RegisterView registerView;
    private final SessionHolder sessionHolder;
    private final StackPane centerContainer;

    private ViewType currentViewType = ViewType.LOGIN;
    private Consumer<SessionHolder.ClientSession> onAuthenticationComplete;

    public AuthUiCoordinator(TcpClient tcpClient) {
        this(tcpClient, new ObjectMapper());
    }

    public AuthUiCoordinator(TcpClient tcpClient, ObjectMapper objectMapper) {
        this.tcpClient = Objects.requireNonNull(tcpClient, "tcpClient must not be null");
        this.sessionHolder = new SessionHolder();

        // 1. Connection Indicator
        this.connectionIndicator = new ConnectionIndicator(tcpClient.getState());
        this.tcpClient.addConnectionListener(this.connectionIndicator);

        // 2. Auth Views
        this.loginView = new LoginView(tcpClient, objectMapper);
        this.registerView = new RegisterView(tcpClient, objectMapper);

        // 3. Navigation wiring
        this.loginView.setOnNavigateToRegister(this::showRegisterView);
        this.registerView.setOnNavigateToLogin(this::showLoginView);

        // 4. Session & Registration handlers
        this.loginView.setOnLoginSuccess((token, data) -> {
            Long userId = (data != null && data.hasNonNull("userId")) ? data.get("userId").asLong() : null;
            String name = (data != null && data.hasNonNull("name")) ? data.get("name").asText() : "";
            String email = (data != null && data.hasNonNull("email")) ? data.get("email").asText() : "";
            Role role = Role.STUDENT;
            if (data != null && data.hasNonNull("role")) {
                role = Role.fromString(data.get("role").asText());
            }

            sessionHolder.setSession(token, userId, name, email, role);

            if (onAuthenticationComplete != null) {
                onAuthenticationComplete.accept(sessionHolder.getSession());
            }
        });

        this.registerView.setOnRegisterSuccess(data -> {
            String registeredEmail = (data != null && data.hasNonNull("email")) ? data.get("email").asText() : "";
            showLoginView();
            if (!registeredEmail.isBlank()) {
                loginView.getEmailField().setText(registeredEmail);
                loginView.getPasswordField().clear();
            }
            loginView.showError(null); // clear errors
        });

        // 5. Layout Assembly
        setStyle("-fx-background-color: #F1F5F9;");

        // Header: Brand on left, ConnectionIndicator on right
        HBox topBar = new HBox();
        topBar.setAlignment(Pos.CENTER_RIGHT);
        topBar.setPadding(new Insets(16, 24, 8, 24));
        topBar.getChildren().add(connectionIndicator);
        setTop(topBar);

        // Center: Card container
        centerContainer = new StackPane();
        centerContainer.setAlignment(Pos.CENTER);
        centerContainer.setPadding(new Insets(16, 20, 32, 20));
        setCenter(centerContainer);

        // Initial view
        showLoginView();
    }

    /**
     * Transitions view to {@link LoginView}.
     */
    public void showLoginView() {
        this.currentViewType = ViewType.LOGIN;
        FxThreadUtils.runOnFxThread(() -> {
            centerContainer.getChildren().setAll(loginView);
            loginView.clearError();
        });
    }

    /**
     * Transitions view to {@link RegisterView}.
     */
    public void showRegisterView() {
        this.currentViewType = ViewType.REGISTER;
        FxThreadUtils.runOnFxThread(() -> {
            centerContainer.getChildren().setAll(registerView);
            registerView.clearError();
        });
    }

    public ViewType getCurrentViewType() {
        return currentViewType;
    }

    public Node getCurrentView() {
        return currentViewType == ViewType.LOGIN ? loginView : registerView;
    }

    public ConnectionIndicator getConnectionIndicator() {
        return connectionIndicator;
    }

    public LoginView getLoginView() {
        return loginView;
    }

    public RegisterView getRegisterView() {
        return registerView;
    }

    public SessionHolder getSessionHolder() {
        return sessionHolder;
    }

    public TcpClient getTcpClient() {
        return tcpClient;
    }

    public void setOnAuthenticationComplete(Consumer<SessionHolder.ClientSession> onAuthenticationComplete) {
        this.onAuthenticationComplete = onAuthenticationComplete;
    }
}
