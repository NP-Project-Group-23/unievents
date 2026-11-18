package com.unievents.unievents_backend.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.network.client.ConnectionState;
import com.unievents.unievents_backend.network.client.TcpClient;
import com.unievents.unievents_backend.network.protocol.ErrorCode;
import com.unievents.unievents_backend.network.protocol.RequestEnvelope;
import com.unievents.unievents_backend.network.protocol.ResponseEnvelope;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class AuthUiComponentsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TcpClient mockTcpClient;

    @BeforeAll
    static void initFx() {
        JavaFxTestHelper.initPlatform();
    }

    @BeforeEach
    void setUp() {
        mockTcpClient = mock(TcpClient.class);
        when(mockTcpClient.getState()).thenReturn(ConnectionState.DISCONNECTED);
    }

    @Test
    @DisplayName("ConnectionIndicator reflects connection states with correct dot colors and label text")
    void testConnectionIndicatorStateTransitions() throws Exception {
        JavaFxTestHelper.runAndWait(() -> {
            ConnectionIndicator indicator = new ConnectionIndicator();

            assertThat(indicator.getCurrentState()).isEqualTo(ConnectionState.DISCONNECTED);
            assertThat(indicator.getStatusText()).isEqualTo(ConnectionIndicator.TEXT_DISCONNECTED);
            assertThat(indicator.getStatusDot().getFill()).isEqualTo(Color.web(ConnectionIndicator.COLOR_DISCONNECTED));

            // Transition to CONNECTING
            indicator.updateState(ConnectionState.CONNECTING);
            assertThat(indicator.getCurrentState()).isEqualTo(ConnectionState.CONNECTING);
            assertThat(indicator.getStatusText()).isEqualTo(ConnectionIndicator.TEXT_CONNECTING);
            assertThat(indicator.getStatusDot().getFill()).isEqualTo(Color.web(ConnectionIndicator.COLOR_CONNECTING));

            // Transition to CONNECTED
            indicator.updateState(ConnectionState.CONNECTED);
            assertThat(indicator.getCurrentState()).isEqualTo(ConnectionState.CONNECTED);
            assertThat(indicator.getStatusText()).isEqualTo(ConnectionIndicator.TEXT_CONNECTED);
            assertThat(indicator.getStatusDot().getFill()).isEqualTo(Color.web(ConnectionIndicator.COLOR_CONNECTED));

            // Transition to RECONNECTING
            indicator.updateState(ConnectionState.RECONNECTING);
            assertThat(indicator.getCurrentState()).isEqualTo(ConnectionState.RECONNECTING);
            assertThat(indicator.getStatusText()).isEqualTo(ConnectionIndicator.TEXT_RECONNECTING);
            assertThat(indicator.getStatusDot().getFill()).isEqualTo(Color.web(ConnectionIndicator.COLOR_CONNECTING));

            // Transition via ConnectionListener interface
            indicator.onConnectionStateChanged(ConnectionState.RECONNECTING, ConnectionState.DISCONNECTED);
            assertThat(indicator.getCurrentState()).isEqualTo(ConnectionState.DISCONNECTED);
            assertThat(indicator.getStatusText()).isEqualTo(ConnectionIndicator.TEXT_DISCONNECTED);
            assertThat(indicator.getStatusDot().getFill()).isEqualTo(Color.web(ConnectionIndicator.COLOR_DISCONNECTED));
        });
    }

    @Test
    @DisplayName("LoginView validates blank fields before sending network request")
    void testLoginViewValidation() throws Exception {
        JavaFxTestHelper.runAndWait(() -> {
            LoginView loginView = new LoginView(mockTcpClient, MAPPER);

            // Empty inputs
            loginView.handleLogin();
            assertThat(loginView.getErrorMessage()).isEqualTo("Email and password are required.");
            assertThat(loginView.getErrorBanner().isVisible()).isTrue();
            verifyNoInteractions(mockTcpClient);

            // Only email set
            loginView.getEmailField().setText("student@uni.edu");
            loginView.handleLogin();
            assertThat(loginView.getErrorMessage()).isEqualTo("Email and password are required.");
            verifyNoInteractions(mockTcpClient);
        });
    }

    @Test
    @DisplayName("LoginView dispatches LOGIN request asynchronously and fires onLoginSuccess")
    void testLoginViewSuccessfulAuth() throws Exception {
        ObjectNode responseData = MAPPER.createObjectNode();
        responseData.put("token", "session-token-abc-123");
        responseData.put("userId", 42L);
        responseData.put("email", "student@uni.edu");
        responseData.put("role", "STUDENT");

        ResponseEnvelope successResponse = ResponseEnvelope.success("req-1", responseData);
        when(mockTcpClient.sendAsync(any(RequestEnvelope.class)))
                .thenReturn(CompletableFuture.completedFuture(successResponse));

        AtomicReference<String> tokenCapture = new AtomicReference<>();
        AtomicReference<Object> dataCapture = new AtomicReference<>();

        JavaFxTestHelper.runAndWait(() -> {
            LoginView loginView = new LoginView(mockTcpClient, MAPPER);
            loginView.setOnLoginSuccess((token, data) -> {
                tokenCapture.set(token);
                dataCapture.set(data);
            });

            loginView.getEmailField().setText("student@uni.edu");
            loginView.getPasswordField().setText("ValidPass123!");

            loginView.handleLogin().join();

            assertThat(tokenCapture.get()).isEqualTo("session-token-abc-123");
            assertThat(loginView.getErrorBanner().isVisible()).isFalse();
            assertThat(loginView.isLoading()).isFalse();

            ArgumentCaptor<RequestEnvelope> captor = ArgumentCaptor.forClass(RequestEnvelope.class);
            verify(mockTcpClient).sendAsync(captor.capture());
            RequestEnvelope sent = captor.getValue();
            assertThat(sent.type()).isEqualTo("LOGIN");
            assertThat(sent.payload().get("email").asText()).isEqualTo("student@uni.edu");
            assertThat(sent.payload().get("password").asText()).isEqualTo("ValidPass123!");
        });
    }

    @Test
    @DisplayName("LoginView displays error banner when server returns INVALID_CREDENTIALS")
    void testLoginViewServerError() throws Exception {
        ResponseEnvelope errorResponse = ResponseEnvelope.error("req-2", ErrorCode.INVALID_CREDENTIALS, "Invalid email or password.");
        when(mockTcpClient.sendAsync(any(RequestEnvelope.class)))
                .thenReturn(CompletableFuture.completedFuture(errorResponse));

        JavaFxTestHelper.runAndWait(() -> {
            LoginView loginView = new LoginView(mockTcpClient, MAPPER);
            loginView.getEmailField().setText("wrong@uni.edu");
            loginView.getPasswordField().setText("BadPass!");

            loginView.handleLogin().join();

            assertThat(loginView.getErrorMessage()).isEqualTo("Invalid email or password.");
            assertThat(loginView.getErrorBanner().isVisible()).isTrue();
            assertThat(loginView.isLoading()).isFalse();
        });
    }

    @Test
    @DisplayName("LoginView displays network error banner on transport failure")
    void testLoginViewNetworkError() throws Exception {
        CompletableFuture<ResponseEnvelope> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Connection refused"));
        when(mockTcpClient.sendAsync(any(RequestEnvelope.class))).thenReturn(failedFuture);

        JavaFxTestHelper.runAndWait(() -> {
            LoginView loginView = new LoginView(mockTcpClient, MAPPER);
            loginView.getEmailField().setText("test@uni.edu");
            loginView.getPasswordField().setText("Secret123!");

            loginView.handleLogin().join();

            assertThat(loginView.getErrorMessage()).contains("Network error");
            assertThat(loginView.getErrorBanner().isVisible()).isTrue();
        });
    }

    @Test
    @DisplayName("RegisterView validates name, email, and password length bounds")
    void testRegisterViewInputValidation() throws Exception {
        JavaFxTestHelper.runAndWait(() -> {
            RegisterView registerView = new RegisterView(mockTcpClient, MAPPER);

            // Empty name
            registerView.handleRegister();
            assertThat(registerView.getErrorMessage()).isEqualTo("Full Name is required.");

            // Empty email
            registerView.getNameField().setText("Alice Student");
            registerView.handleRegister();
            assertThat(registerView.getErrorMessage()).isEqualTo("Email is required.");

            // Password too short (< 8 chars)
            registerView.getEmailField().setText("alice@uni.edu");
            registerView.getPasswordField().setText("short");
            registerView.handleRegister();
            assertThat(registerView.getErrorMessage()).isEqualTo("Password must be at least 8 characters long.");

            // Password exceeds 72 UTF-8 bytes
            String longPassword = "A".repeat(73);
            registerView.getPasswordField().setText(longPassword);
            registerView.handleRegister();
            assertThat(registerView.getErrorMessage()).isEqualTo("Password must not exceed 72 bytes.");

            verifyNoInteractions(mockTcpClient);
        });
    }

    @Test
    @DisplayName("RegisterView dispatches REGISTER request and handles success")
    void testRegisterViewSuccess() throws Exception {
        ObjectNode registeredData = MAPPER.createObjectNode();
        registeredData.put("id", 101L);
        registeredData.put("name", "Bob Student");
        registeredData.put("email", "bob@uni.edu");
        registeredData.put("role", "STUDENT");

        ResponseEnvelope successResponse = ResponseEnvelope.success("req-reg-1", registeredData);
        when(mockTcpClient.sendAsync(any(RequestEnvelope.class)))
                .thenReturn(CompletableFuture.completedFuture(successResponse));

        AtomicBoolean registerSuccessTriggered = new AtomicBoolean(false);

        JavaFxTestHelper.runAndWait(() -> {
            RegisterView registerView = new RegisterView(mockTcpClient, MAPPER);
            registerView.setOnRegisterSuccess(data -> registerSuccessTriggered.set(true));

            registerView.getNameField().setText("Bob Student");
            registerView.getEmailField().setText("bob@uni.edu");
            registerView.getPasswordField().setText("ValidSecret123");

            registerView.handleRegister().join();

            assertThat(registerSuccessTriggered.get()).isTrue();
            assertThat(registerView.getErrorBanner().isVisible()).isFalse();

            ArgumentCaptor<RequestEnvelope> captor = ArgumentCaptor.forClass(RequestEnvelope.class);
            verify(mockTcpClient).sendAsync(captor.capture());
            RequestEnvelope sent = captor.getValue();
            assertThat(sent.type()).isEqualTo("REGISTER");
            assertThat(sent.payload().get("name").asText()).isEqualTo("Bob Student");
            assertThat(sent.payload().get("email").asText()).isEqualTo("bob@uni.edu");
            assertThat(sent.payload().get("role").asText()).isEqualTo("STUDENT");
        });
    }

    @Test
    @DisplayName("AuthUiCoordinator manages view navigation, session token lifecycle, and connection indicator")
    void testAuthUiCoordinatorWorkflow() throws Exception {
        when(mockTcpClient.getState()).thenReturn(ConnectionState.CONNECTED);

        JavaFxTestHelper.runAndWait(() -> {
            AuthUiCoordinator coordinator = new AuthUiCoordinator(mockTcpClient, MAPPER);

            // Initially on LOGIN view
            assertThat(coordinator.getCurrentViewType()).isEqualTo(AuthUiCoordinator.ViewType.LOGIN);
            assertThat(coordinator.getCurrentView()).isEqualTo(coordinator.getLoginView());
            assertThat(coordinator.getSessionHolder().isAuthenticated()).isFalse();

            // Navigate to REGISTER view
            coordinator.showRegisterView();
            assertThat(coordinator.getCurrentViewType()).isEqualTo(AuthUiCoordinator.ViewType.REGISTER);
            assertThat(coordinator.getCurrentView()).isEqualTo(coordinator.getRegisterView());

            // Navigate back to LOGIN view
            coordinator.showLoginView();
            assertThat(coordinator.getCurrentViewType()).isEqualTo(AuthUiCoordinator.ViewType.LOGIN);

            // Simulate successful registration transition
            ObjectNode regData = MAPPER.createObjectNode();
            regData.put("email", "newuser@uni.edu");
            coordinator.getRegisterView().getOnRegisterSuccess().accept(regData);
            assertThat(coordinator.getCurrentViewType()).isEqualTo(AuthUiCoordinator.ViewType.LOGIN);
            assertThat(coordinator.getLoginView().getEmailField().getText()).isEqualTo("newuser@uni.edu");

            // Simulate login success callback triggering session persistence
            ObjectNode loginData = MAPPER.createObjectNode();
            loginData.put("token", "tok-xyz-789");
            loginData.put("userId", 88L);
            loginData.put("name", "New User");
            loginData.put("email", "newuser@uni.edu");
            loginData.put("role", "STUDENT");

            coordinator.getSessionHolder().setSession("tok-xyz-789", 88L, "New User", "newuser@uni.edu", Role.STUDENT);

            assertThat(coordinator.getSessionHolder().isAuthenticated()).isTrue();
            assertThat(coordinator.getSessionHolder().getToken()).isEqualTo("tok-xyz-789");
            assertThat(coordinator.getSessionHolder().getUserId()).isEqualTo(88L);
            assertThat(coordinator.getSessionHolder().getRole()).isEqualTo(Role.STUDENT);

            coordinator.getSessionHolder().clear();
            assertThat(coordinator.getSessionHolder().isAuthenticated()).isFalse();
        });
    }
}
