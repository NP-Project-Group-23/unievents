package com.unievents.unievents_backend.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * JavaFX desktop view for student authentication.
 * Provides email and password input fields, loading indicator during in-flight network requests,
 * descriptive error alert banner, and switch-to-register navigation.
 * All TCP communication is dispatched asynchronously via {@link TcpClient#sendAsync(RequestEnvelope)}.
 */
public class LoginView extends VBox {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TcpClient tcpClient;
    private final ObjectMapper objectMapper;

    // UI Controls
    private final Label titleLabel;
    private final Label subtitleLabel;
    private final Label errorBanner;
    private final TextField emailField;
    private final PasswordField passwordField;
    private final Button loginButton;
    private final ProgressIndicator loadingSpinner;
    private final Hyperlink registerLink;

    // Callbacks
    private BiConsumer<String, JsonNode> onLoginSuccess;
    private Runnable onNavigateToRegister;

    public LoginView(TcpClient tcpClient) {
        this(tcpClient, MAPPER);
    }

    public LoginView(TcpClient tcpClient, ObjectMapper objectMapper) {
        this.tcpClient = Objects.requireNonNull(tcpClient, "tcpClient must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : MAPPER;

        setAlignment(Pos.CENTER);
        setSpacing(16.0);
        setPadding(new Insets(32, 40, 32, 40));
        setMaxWidth(420);
        setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 12px; " +
                "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 16, 0, 0, 4);");

        // Title and Subtitle
        titleLabel = new Label("Student Sign In");
        titleLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1E293B;");

        subtitleLabel = new Label("Enter your university credentials to continue");
        subtitleLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748B;");

        // Error Banner (hidden initially)
        errorBanner = new Label();
        errorBanner.setId("login-error-banner");
        errorBanner.setWrapText(true);
        errorBanner.setMaxWidth(Double.MAX_VALUE);
        errorBanner.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #991B1B; " +
                "-fx-padding: 10px 14px; -fx-background-radius: 8px; -fx-border-color: #F87171; " +
                "-fx-border-radius: 8px; -fx-border-width: 1px; -fx-font-size: 12px;");
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);

        // Input Fields
        Label emailLabel = new Label("Email Address");
        emailLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        emailField = new TextField();
        emailField.setId("login-email-field");
        emailField.setPromptText("student@university.edu");
        emailField.setStyle("-fx-padding: 10px; -fx-background-radius: 6px; -fx-border-color: #CBD5E1; -fx-border-radius: 6px;");

        Label passwordLabel = new Label("Password");
        passwordLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        passwordField = new PasswordField();
        passwordField.setId("login-password-field");
        passwordField.setPromptText("••••••••");
        passwordField.setStyle("-fx-padding: 10px; -fx-background-radius: 6px; -fx-border-color: #CBD5E1; -fx-border-radius: 6px;");

        VBox emailBox = new VBox(6, emailLabel, emailField);
        VBox passwordBox = new VBox(6, passwordLabel, passwordField);

        // Loading spinner & Submit button
        loadingSpinner = new ProgressIndicator();
        loadingSpinner.setId("login-loading-spinner");
        loadingSpinner.setPrefSize(22, 22);
        loadingSpinner.setVisible(false);
        loadingSpinner.setManaged(false);

        loginButton = new Button("Sign In");
        loginButton.setId("login-submit-button");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setStyle("-fx-background-color: #2563EB; -fx-text-fill: #FFFFFF; " +
                "-fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10px 16px; -fx-background-radius: 6px; -fx-cursor: hand;");

        HBox buttonContainer = new HBox(8, loginButton, loadingSpinner);
        buttonContainer.setAlignment(Pos.CENTER);
        HBox.setHgrow(loginButton, Priority.ALWAYS);

        loginButton.setOnAction(e -> handleLogin());
        passwordField.setOnAction(e -> handleLogin());

        // Switch to registration link
        Label registerPrompt = new Label("Don't have an account?");
        registerPrompt.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748B;");

        registerLink = new Hyperlink("Register here");
        registerLink.setId("login-register-link");
        registerLink.setStyle("-fx-font-size: 13px; -fx-text-fill: #2563EB; -fx-border-color: transparent; -fx-padding: 0;");
        registerLink.setOnAction(e -> {
            if (onNavigateToRegister != null) {
                onNavigateToRegister.run();
            }
        });

        HBox switchBox = new HBox(4, registerPrompt, registerLink);
        switchBox.setAlignment(Pos.CENTER);

        getChildren().addAll(
                titleLabel,
                subtitleLabel,
                errorBanner,
                emailBox,
                passwordBox,
                buttonContainer,
                switchBox
        );
    }

    /**
     * Executes the login process asynchronously. Validates input, builds the LOGIN request envelope,
     * and dispatches via {@link TcpClient#sendAsync(RequestEnvelope)}.
     */
    public CompletableFuture<ResponseEnvelope> handleLogin() {
        String email = emailField.getText() != null ? emailField.getText().trim() : "";
        String password = passwordField.getText() != null ? passwordField.getText() : "";

        if (email.isEmpty() || password.isEmpty()) {
            showError("Email and password are required.");
            return CompletableFuture.completedFuture(null);
        }

        setLoading(true);
        clearError();

        String requestId = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("email", email);
        payload.put("password", password);

        RequestEnvelope request = new RequestEnvelope(requestId, "LOGIN", null, payload);

        return tcpClient.sendAsync(request)
                .thenApply(response -> {
                    FxThreadUtils.runOnFxThread(() -> {
                        setLoading(false);
                        if (response != null && response.isSuccess()) {
                            clearError();
                            JsonNode data = response.data();
                            String token = (data != null && data.hasNonNull("token")) ? data.get("token").asText() : null;
                            if (onLoginSuccess != null) {
                                onLoginSuccess.accept(token, data);
                            }
                        } else {
                            String errorMsg = (response != null && response.errorMessage() != null)
                                    ? response.errorMessage()
                                    : "Login failed. Please check your credentials.";
                            showError(errorMsg);
                        }
                    });
                    return response;
                })
                .exceptionally(ex -> {
                    FxThreadUtils.runOnFxThread(() -> {
                        setLoading(false);
                        String detail = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        showError("Network error: " + (detail != null ? detail : "Unable to reach server"));
                    });
                    return null;
                });
    }

    public void setLoading(boolean loading) {
        FxThreadUtils.runOnFxThread(() -> {
            emailField.setDisable(loading);
            passwordField.setDisable(loading);
            loginButton.setDisable(loading);
            registerLink.setDisable(loading);
            loadingSpinner.setVisible(loading);
            loadingSpinner.setManaged(loading);
        });
    }

    public void showError(String message) {
        FxThreadUtils.runOnFxThread(() -> {
            if (message != null && !message.isBlank()) {
                errorBanner.setText(message);
                errorBanner.setVisible(true);
                errorBanner.setManaged(true);
            } else {
                clearError();
            }
        });
    }

    public void clearError() {
        FxThreadUtils.runOnFxThread(() -> {
            errorBanner.setText("");
            errorBanner.setVisible(false);
            errorBanner.setManaged(false);
        });
    }

    public void setOnLoginSuccess(BiConsumer<String, JsonNode> onLoginSuccess) {
        this.onLoginSuccess = onLoginSuccess;
    }

    public BiConsumer<String, JsonNode> getOnLoginSuccess() {
        return onLoginSuccess;
    }

    public void setOnNavigateToRegister(Runnable onNavigateToRegister) {
        this.onNavigateToRegister = onNavigateToRegister;
    }

    public Runnable getOnNavigateToRegister() {
        return onNavigateToRegister;
    }

    public TextField getEmailField() {
        return emailField;
    }

    public PasswordField getPasswordField() {
        return passwordField;
    }

    public Button getLoginButton() {
        return loginButton;
    }

    public Hyperlink getRegisterLink() {
        return registerLink;
    }

    public Label getErrorBanner() {
        return errorBanner;
    }

    public ProgressIndicator getLoadingSpinner() {
        return loadingSpinner;
    }

    public String getErrorMessage() {
        return errorBanner.getText();
    }

    public boolean isLoading() {
        return loadingSpinner.isVisible();
    }
}
