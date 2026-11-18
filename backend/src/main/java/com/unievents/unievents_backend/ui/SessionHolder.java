package com.unievents.unievents_backend.ui;

import com.unievents.unievents_backend.domain.model.Role;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe client-side container holding the active authenticated session token
 * and associated user identity context.
 */
public class SessionHolder {

    public record ClientSession(
            String token,
            Long userId,
            String name,
            String email,
            Role role
    ) {
        public ClientSession {
            Objects.requireNonNull(token, "token must not be null");
        }
    }

    private final AtomicReference<ClientSession> currentSession = new AtomicReference<>(null);

    public void setSession(String token, Long userId, String name, String email, Role role) {
        if (token == null || token.isBlank()) {
            currentSession.set(null);
        } else {
            currentSession.set(new ClientSession(token, userId, name, email, role != null ? role : Role.STUDENT));
        }
    }

    public void setSession(ClientSession session) {
        currentSession.set(session);
    }

    public ClientSession getSession() {
        return currentSession.get();
    }

    public String getToken() {
        ClientSession session = currentSession.get();
        return session != null ? session.token() : null;
    }

    public Long getUserId() {
        ClientSession session = currentSession.get();
        return session != null ? session.userId() : null;
    }

    public String getEmail() {
        ClientSession session = currentSession.get();
        return session != null ? session.email() : null;
    }

    public Role getRole() {
        ClientSession session = currentSession.get();
        return session != null ? session.role() : null;
    }

    public boolean isAuthenticated() {
        return currentSession.get() != null;
    }

    public void clear() {
        currentSession.set(null);
    }
}
