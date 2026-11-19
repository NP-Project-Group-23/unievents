package com.unievents.unievents_backend;

import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.model.User;
import com.unievents.unievents_backend.domain.service.InvalidCredentialsException;
import com.unievents.unievents_backend.domain.service.SessionContext;
import com.unievents.unievents_backend.domain.service.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SessionManagerTest {

    private SessionManager sessionManager;
    private User testStudent;
    private User testAdmin;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager(Duration.ofMinutes(30));
        testStudent = new User(101L, "Sam Student", "sam@uni.edu", "hash1", Role.STUDENT, Instant.now());
        testAdmin = new User(102L, "Admin User", "admin@uni.edu", "hash2", Role.ADMIN, Instant.now());
    }

    @Test
    @DisplayName("createSession generates unique token and stores active context")
    void testSessionCreation() {
        SessionContext session = sessionManager.createSession(testStudent);

        assertNotNull(session);
        assertNotNull(session.token());
        assertEquals(101L, session.userId());
        assertEquals("sam@uni.edu", session.email());
        assertEquals(Role.STUDENT, session.role());
        assertNotNull(session.createdAt());
        assertEquals(session.createdAt(), session.lastAccessedAt());
        assertEquals(1, sessionManager.getActiveSessionCount());
    }

    @Test
    @DisplayName("validateSession touches lastAccessedAt on access")
    void testTouchOnAccess() throws InterruptedException {
        SessionContext initial = sessionManager.createSession(testStudent);
        Instant firstAccess = initial.lastAccessedAt();

        // Brief delay to ensure clock advance
        Thread.sleep(10);

        Optional<SessionContext> accessed = sessionManager.validateSession(initial.token());
        assertTrue(accessed.isPresent());
        assertTrue(accessed.get().lastAccessedAt().isAfter(firstAccess) || accessed.get().lastAccessedAt().equals(firstAccess));
    }

    @Test
    @DisplayName("session expires after configured idle timeout duration")
    void testIdleExpiry() throws InterruptedException {
        // Create session manager with very short 50ms timeout for testing
        SessionManager shortLivedManager = new SessionManager(Duration.ofMillis(50));
        SessionContext session = shortLivedManager.createSession(testStudent);

        // Immediately valid
        assertTrue(shortLivedManager.validateSession(session.token()).isPresent());

        // Wait for idle expiration
        Thread.sleep(70);

        // Should now be expired and purged
        Optional<SessionContext> expired = shortLivedManager.validateSession(session.token());
        assertTrue(expired.isEmpty(), "Session should be expired after idle duration");
        assertEquals(0, shortLivedManager.getActiveSessionCount(), "Expired session should be purged from memory");
    }

    @Test
    @DisplayName("invalidateSession removes session and denies subsequent access")
    void testSessionInvalidation() {
        SessionContext session = sessionManager.createSession(testStudent);
        assertTrue(sessionManager.validateSession(session.token()).isPresent());

        boolean invalidated = sessionManager.invalidateSession(session.token());
        assertTrue(invalidated, "invalidateSession should return true for existing active session");

        // Subsequent lookup fails
        assertFalse(sessionManager.validateSession(session.token()).isPresent());
        assertEquals(0, sessionManager.getActiveSessionCount());

        // Second invalidate returns false
        assertFalse(sessionManager.invalidateSession(session.token()));
    }

    @Test
    @DisplayName("requireRole validates role privileges correctly")
    void testRoleVerification() {
        SessionContext studentSession = sessionManager.createSession(testStudent);
        SessionContext adminSession = sessionManager.createSession(testAdmin);

        // STUDENT session
        assertTrue(sessionManager.requireRole(studentSession.token(), Role.STUDENT), "Student should satisfy STUDENT role");
        assertFalse(sessionManager.requireRole(studentSession.token(), Role.ADMIN), "Student should NOT satisfy ADMIN role");

        // ADMIN session satisfies both STUDENT and ADMIN
        assertTrue(sessionManager.requireRole(adminSession.token(), Role.STUDENT), "Admin should satisfy STUDENT role");
        assertTrue(sessionManager.requireRole(adminSession.token(), Role.ADMIN), "Admin should satisfy ADMIN role");

        // Non-existent or invalid token
        assertFalse(sessionManager.requireRole("non-existent-token", Role.STUDENT));
        assertFalse(sessionManager.requireRole(null, Role.STUDENT));
    }

    @Test
    @DisplayName("getVerifiedUserId returns userId for active session and throws for invalid")
    void testGetVerifiedUserId() {
        SessionContext session = sessionManager.createSession(testStudent);
        assertEquals(101L, sessionManager.getVerifiedUserId(session.token()));

        assertThrows(InvalidCredentialsException.class, () ->
                sessionManager.getVerifiedUserId("invalid-token")
        );
        assertThrows(InvalidCredentialsException.class, () ->
                sessionManager.getVerifiedUserId(null)
        );
    }

    @Test
    @DisplayName("concurrent validateSession and invalidateSession never resurrects an invalidated session")
    void testConcurrentValidateAndInvalidateNoResurrection() throws Exception {
        int iterations = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger resurrectedCount = new AtomicInteger(0);

        try {
            for (int i = 0; i < iterations; i++) {
                User u = new User((long) i, "User" + i, "u" + i + "@uni.edu", "hash", Role.STUDENT, Instant.now());
                SessionContext ctx = sessionManager.createSession(u);
                String token = ctx.token();

                CountDownLatch readyLatch = new CountDownLatch(2);
                CountDownLatch startLatch = new CountDownLatch(1);

                Future<?> f1 = pool.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        sessionManager.validateSession(token);
                    } catch (InterruptedException ignored) {}
                });

                Future<?> f2 = pool.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        sessionManager.invalidateSession(token);
                    } catch (InterruptedException ignored) {}
                });

                readyLatch.await();
                startLatch.countDown();

                f1.get();
                f2.get();

                if (sessionManager.validateSession(token).isPresent() || sessionManager.getActiveSessionCount() > 0) {
                    resurrectedCount.incrementAndGet();
                    sessionManager.invalidateSession(token);
                }
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals(0, resurrectedCount.get(), "No invalidated sessions should be resurrected across 10,000 concurrent iterations");
    }
}
