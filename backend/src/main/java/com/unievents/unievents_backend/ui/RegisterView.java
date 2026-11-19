package com.unievents.unievents_backend.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * JavaFX desktop view for student account registration.
 * Collects student full name, university email, password (minimum 8 characters, max 72 bytes),
 * and role (defaulted to STUDENT).
 * All TCP registration requests are dispatched asynchronously via {@link TcpClient#sendAsync(RequestEnvelope)}.
 */
public class RegisterView extends VBox {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TcpClient tcpClient;
    private final ObjectMapper objectMapper;

    // UI Controls
    private final Label titleLabel;
    private final Label subtitleLabel;
    private final Label errorBanner;
    private final TextField nameField;
    private final TextField emailField;
    private final PasswordField passwordField;
    private final ComboBox<Role> roleComboBox;
    private final Button registerButton;
    private final ProgressIndicator loadingSpinner;
    private final Hyperlink loginLink;

    // Callbacks
    private Consumer<JsonNode> onRegisterSuccess;
    private Runnable onNavigateToLogin;

    public RegisterView(TcpClient tcpClient) {
        this(tcpClient, MAPPER);
    }

    public RegisterView(TcpClient tcpClient, ObjectMapper objectMapper) {
        this.tcpClient = Objects.requireNonNull(tcpClient, "tcpClient must not be null");
        this.objectMapper = objectMapper != null ? objectMapper : MAPPER;

        setAlignment(Pos.CENTER);
        setSpacing(14.0);
        setPadding(new Insets(32, 40, 32, 40));
        setMaxWidth(420);
        setStyle("-fx-background-color: #FFFFFF; -fx-background-radius: 12px; " +
                "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 16, 0, 0, 4);");

        // Title and Subtitle
        titleLabel = new Label("Student Registration");
        titleLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1E293B;");

        subtitleLabel = new Label("Create an account to browse and book campus events");
        subtitleLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748B;");

        // Error Banner (hidden initially)
        errorBanner = new Label();
        errorBanner.setId("register-error-banner");
        errorBanner.setWrapText(true);
        errorBanner.setMaxWidth(Double.MAX_VALUE);
        errorBanner.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #991B1B; " +
                "-fx-padding: 10px 14px; -fx-background-radius: 8px; -fx-border-color: #F87171; " +
                "-fx-border-radius: 8px; -fx-border-width: 1px; -fx-font-size: 12px;");
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);

        // Name Field
        Label nameLabel = new Label("Full Name");
        nameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        nameField = new TextField();
        nameField.setId("register-name-field");
        nameField.setPromptText("Jane Doe");
        nameField.setStyle("-fx-padding: 10px; -fx-background-radius: 6px; -fx-border-color: #CBD5E1; -fx-border-radius: 6px;");

        // Email Field
        Label emailLabel = new Label("University Email");
        emailLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        emailField = new TextField();
        emailField.setId("register-email-field");
        emailField.setPromptText("student@university.edu");
        emailField.setStyle("-fx-padding: 10px; -fx-background-radius: 6px; -fx-border-color: #CBD5E1; -fx-border-radius: 6px;");

        // Password Field
        Label passwordLabel = new Label("Password (min. 8 characters)");
        passwordLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        passwordField = new PasswordField();
        passwordField.setId("register-password-field");
        passwordField.setPromptText("••••••••");
        passwordField.setStyle("-fx-padding: 10px; -fx-background-radius: 6px; -fx-border-color: #CBD5E1; -fx-border-radius: 6px;");

        // Role Selector (defaults and locked to STUDENT)
        Label roleLabel = new Label("Account Role");
        roleLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #334155;");

        roleComboBox = new ComboBox<>(FXCollections.observableArrayList(Role.STUDENT));
        roleComboBox.setId("register-role-combo");
        roleComboBox.setValue(Role.STUDENT);
        roleComboBox.setDisable(true); // Locked to STUDENT for public registration
        roleComboBox.setMaxWidth(Double.MAX_VALUE);
        roleComboBox.setStyle("-fx-padding: 6px; -fx-background-radius: 6px; -fx-border-color: #CBD5E1; -fx-border-radius: 6px;");

        VBox nameBox = new VBox(4, nameLabel, nameField);
        VBox emailBox = new VBox(4, emailLabel, emailField);
        VBox passwordBox = new VBox(4, passwordLabel, passwordField);
        VBox roleBox = new VBox(4, roleLabel, roleComboBox);

        // Loading spinner & Submit button
        loadingSpinner = new ProgressIndicator();
        loadingSpinner.setId("register-loading-spinner");
        loadingSpinner.setPrefSize(22, 22);
        loadingSpinner.setVisible(false);
        loadingSpinner.setManaged(false);

        registerButton = new Button("Create Account");
        registerButton.setId("register-submit-button");
        registerButton.setMaxWidth(Double.MAX_VALUE);
        registerButton.setStyle("-fx-background-color: #059669; -fx-text-fill: #FFFFFF; " +
                "-fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 10px 16px; -fx-background-radius: 6px; -fx-cursor: hand;");

        HBox buttonContainer = new HBox(8, registerButton, loadingSpinner);
        buttonContainer.setAlignment(Pos.CENTER);
        HBox.setHgrow(registerButton, Priority.ALWAYS);

        registerButton.setOnAction(e -> handleRegister());
        passwordField.setOnAction(e -> handleRegister());

        // Switch to login link
        Label loginPrompt = new Label("Already have an account?");
        loginPrompt.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748B;");

        loginLink = new Hyperlink("Sign In here");
        loginLink.setId("register-login-link");
        loginLink.setStyle("-fx-font-size: 13px; -fx-text-fill: #059669; -fx-border-color: transparent; -fx-padding: 0;");
        loginLink.setOnAction(e -> {
            if (onNavigateToLogin != null) {
                onNavigateToLogin.run();
            }
        });

        HBox switchBox = new HBox(4, loginPrompt, loginLink);
        switchBox.setAlignment(Pos.CENTER);

        getChildren().addAll(
                titleLabel,
                subtitleLabel,
                errorBanner,
                nameBox,
                emailBox,
                passwordBox,
                roleBox,
                buttonContainer,
                switchBox
        );
    }

    /**
     * Executes the registration process asynchronously. Validates input bounds,
     * packages the REGISTER payload, and dispatches via {@link TcpClient#sendAsync(RequestEnvelope)}.
     */
    public CompletableFuture<ResponseEnvelope> handleRegister() {
        String name = nameField.getText() != null ? nameField.getText().trim() : "";
        String email = emailField.getText() != null ? emailField.getText().trim() : "";
        String password = passwordField.getText() != null ? passwordField.getText() : "";
        Role role = roleComboBox.getValue() != null ? roleComboBox.getValue() : Role.STUDENT;

        if (name.isEmpty()) {
            showError("Full Name is required.");
            return CompletableFuture.completedFuture(null);
        }
        if (email.isEmpty()) {
            showError("Email is required.");
            return CompletableFuture.completedFuture(null);
        }
        if (password.length() < 8) {
            showError("Password must be at least 8 characters long.");
            return CompletableFuture.completedFuture(null);
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            showError("Password must not exceed 72 bytes.");
            return CompletableFuture.completedFuture(null);
        }

        setLoading(true);
        clearError();

        String requestId = UUID.randomUUID().toString();
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("name", name);
        payload.put("email", email);
        payload.put("password", password);
        payload.put("role", role.name());

        RequestEnvelope request = new RequestEnvelope(requestId, "REGISTER", null, payload);

        return tcpClient.sendAsync(request)
                .thenApply(response -> {
                    FxThreadUtils.runOnFxThread(() -> {
                        setLoading(false);
                        if (response != null && response.isSuccess()) {
                            clearError();
                            if (onRegisterSuccess != null) {
                                onRegisterSuccess.accept(response.data());
                            }
                        } else {
                            String errorMsg = (response != null && response.errorMessage() != null)
                                    ? response.errorMessage()
                                    : "Registration failed.";
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
            nameField.setDisable(loading);
            emailField.setDisable(loading);
            passwordField.setDisable(loading);
            registerButton.setDisable(loading);
            loginLink.setDisable(loading);
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

    public void setOnRegisterSuccess(Consumer<JsonNode> onRegisterSuccess) {
        this.onRegisterSuccess = onRegisterSuccess;
    }

    public Consumer<JsonNode> getOnRegisterSuccess() {
        return onRegisterSuccess;
    }

    public void setOnNavigateToLogin(Runnable onNavigateToLogin) {
        this.onNavigateToLogin = onNavigateToLogin;
    }

    public Runnable getOnNavigateToLogin() {
        return onNavigateToLogin;
    }

    public TextField getNameField() {
        return nameField;
    }

    public TextField getEmailField() {
        return emailField;
    }

    public PasswordField getPasswordField() {
        return passwordField;
    }

    public ComboBox<Role> getRoleComboBox() {
        return roleComboBox;
    }

    public Button getRegisterButton() {
        return registerButton;
    }

    public Hyperlink getLoginLink() {
        return loginLink;
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
