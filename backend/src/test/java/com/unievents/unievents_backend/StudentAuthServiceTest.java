package com.unievents.unievents_backend;

import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.model.User;
import com.unievents.unievents_backend.domain.repository.UserRepository;
import com.unievents.unievents_backend.domain.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class StudentAuthServiceTest {

    private UserRepository userRepository;
    private PasswordHasher passwordHasher;
    private SessionManager sessionManager;
    private StudentAuthService authService;

    // In-memory fake repository for lightning-fast unit tests
    private static class InMemoryUserRepository implements UserRepository {
        private final ConcurrentHashMap<Long, User> usersById = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, User> usersByEmail = new ConcurrentHashMap<>();
        private final AtomicLong idGen = new AtomicLong(100);

        @Override
        public User save(User user) {
            String norm = user.email().strip().toLowerCase();
            if (user.id() == null) {
                if (usersByEmail.containsKey(norm)) {
                    throw new DuplicateEmailException("Duplicate email: " + norm);
                }
                Long id = idGen.incrementAndGet();
                User created = new User(id, user.name(), norm, user.passwordHash(), user.role(), user.createdAt());
                usersById.put(id, created);
                usersByEmail.put(norm, created);
                return created;
            } else {
                usersById.put(user.id(), user);
                usersByEmail.put(norm, user);
                return user;
            }
        }

        @Override
        public Optional<User> findById(Long id) {
            return Optional.ofNullable(usersById.get(id));
        }

        @Override
        public Optional<User> findByEmail(String email) {
            if (email == null) return Optional.empty();
            return Optional.ofNullable(usersByEmail.get(email.strip().toLowerCase()));
        }

        @Override
        public boolean existsByEmail(String email) {
            if (email == null) return false;
            return usersByEmail.containsKey(email.strip().toLowerCase());
        }

        @Override
        public void deleteById(Long id) {
            User removed = usersById.remove(id);
            if (removed != null) {
                usersByEmail.remove(removed.email());
            }
        }
    }

    @BeforeEach
    void setUp() {
        userRepository = new InMemoryUserRepository();
        // Use fast strength 4 for unit tests
        passwordHasher = Mockito.spy(new BCryptPasswordHasher(4));
        sessionManager = new SessionManager();
        authService = new StudentAuthService(userRepository, passwordHasher, sessionManager);
    }

    @Test
    @DisplayName("successful registration creates user with BCrypt hashed password")
    void testSuccessfulRegistration() {
        User user = authService.register("Alice Wonderland", "alice@test.edu", "securePass123", Role.STUDENT);

        assertNotNull(user.id());
        assertEquals("Alice Wonderland", user.name());
        assertEquals("alice@test.edu", user.email());
        assertEquals(Role.STUDENT, user.role());
        assertNotEquals("securePass123", user.passwordHash());
        assertTrue(passwordHasher.verify("securePass123", user.passwordHash()));
    }

    @Test
    @DisplayName("duplicate email registration throws DuplicateEmailException")
    void testDuplicateEmailRejection() {
        authService.register("Alice", "duplicate@test.edu", "pass12345", Role.STUDENT);

        assertThrows(DuplicateEmailException.class, () ->
                authService.register("Alice Clone", "  DUPLICATE@TEST.EDU  ", "otherPassword", Role.STUDENT)
        );
    }

    @Test
    @DisplayName("passwords exceeding 72 UTF-8 bytes are rejected with AuthValidationException")
    void testPasswordExceeding72BytesRejection() {
        // Multi-byte unicode string where 40 chars = 80 UTF-8 bytes
        String longPassword = "é".repeat(40);
        assertTrue(longPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72);

        assertThrows(AuthValidationException.class, () ->
                authService.register("Bob", "bob@test.edu", longPassword, Role.STUDENT)
        );

        // Plain ASCII string > 72 chars
        String longAscii = "A".repeat(73);
        assertThrows(AuthValidationException.class, () ->
                authService.register("Bob", "bob2@test.edu", longAscii, Role.STUDENT)
        );
    }

    @Test
    @DisplayName("passwords shorter than 8 characters are rejected with AuthValidationException")
    void testPasswordLessThan8CharsRejection() {
        assertThrows(AuthValidationException.class, () ->
                authService.register("Charlie", "charlie@test.edu", "short", Role.STUDENT)
        );
    }

    @Test
    @DisplayName("valid login authenticates user and generates active session")
    void testValidLogin() {
        authService.register("Dave", "dave@test.edu", "validPassword123", Role.STUDENT);

        AuthResult result = authService.authenticate("dave@test.edu", "validPassword123");

        assertNotNull(result);
        assertNotNull(result.user());
        assertNotNull(result.token());
        assertEquals("dave@test.edu", result.user().email());

        Optional<SessionContext> session = sessionManager.validateSession(result.token());
        assertTrue(session.isPresent());
        assertEquals(result.user().id(), session.get().userId());
    }

    @Test
    @DisplayName("login with incorrect password throws InvalidCredentialsException")
    void testInvalidPasswordLogin() {
        authService.register("Eve", "eve@test.edu", "correctPassword123", Role.STUDENT);

        assertThrows(InvalidCredentialsException.class, () ->
                authService.authenticate("eve@test.edu", "wrongPassword123")
        );
    }

    @Test
    @DisplayName("login for non-existent email performs dummy verification to prevent timing attack")
    void testNonExistentEmailDummyVerification() {
        // Reset invocations count from constructor dummyHash generation
        clearInvocations(passwordHasher);

        assertThrows(InvalidCredentialsException.class, () ->
                authService.authenticate("ghost@test.edu", "someRandomPassword")
        );

        // Verify passwordHasher.verify was invoked once with the dummy hash
        verify(passwordHasher, times(1)).verify(eq("someRandomPassword"), anyString());
    }
}
