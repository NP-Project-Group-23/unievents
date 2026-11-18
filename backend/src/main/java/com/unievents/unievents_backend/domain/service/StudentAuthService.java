package com.unievents.unievents_backend.domain.service;

import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.model.User;
import com.unievents.unievents_backend.domain.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Core authentication service handling registration, credentials verification,
 * timing-attack resistant authentication, and session issuance.
 */
@Service
public class StudentAuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final UserRepository userRepository;
    private final PasswordHasher passwordHasher;
    private final SessionManager sessionManager;
    private final String dummyHash;

    public StudentAuthService(
            UserRepository userRepository,
            PasswordHasher passwordHasher,
            SessionManager sessionManager
    ) {
        this.userRepository = userRepository;
        this.passwordHasher = passwordHasher;
        this.sessionManager = sessionManager;
        // Pre-computed dummy hash with legitimate work factor for timing attack mitigation
        this.dummyHash = passwordHasher.hash("dummy-constant-time-timing-guard");
    }

    /**
     * Registers a new user with default STUDENT role.
     */
    public User register(String name, String email, String rawPassword) {
        return register(name, email, rawPassword, Role.STUDENT);
    }

    /**
     * Registers a new user with validation, duplicate detection, and BCrypt hashing.
     *
     * @param name        display name (1-100 characters)
     * @param email       unique email address (1-254 characters)
     * @param rawPassword plaintext password (8-72 UTF-8 bytes)
     * @param role        assigned role (STUDENT or ADMIN)
     * @return persisted user entity
     */
    public User register(String name, String email, String rawPassword, Role role) {
        validateRegistrationInput(name, email, rawPassword);

        String normalizedEmail = normalizeEmail(email);

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException("An account with email " + normalizedEmail + " already exists.");
        }

        String passwordHash = passwordHasher.hash(rawPassword);
        Role assignedRole = (role != null) ? role : Role.STUDENT;

        User newUser = new User(null, name.strip(), normalizedEmail, passwordHash, assignedRole, Instant.now());
        return userRepository.save(newUser);
    }

    /**
     * Authenticates credentials using constant-time BCrypt verification.
     * Always executes password verification even for non-existent users to prevent
     * email enumeration and timing analysis.
     *
     * @param email       user email address
     * @param rawPassword user password
     * @return authentication result containing user and session context
     */
    public AuthResult authenticate(String email, String rawPassword) {
        if (email == null || email.isBlank()) {
            throw new InvalidCredentialsException("Email and password are required.");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new InvalidCredentialsException("Email and password are required.");
        }

        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > BCryptPasswordHasher.MAX_BYTES) {
            // Defend against algorithmic complexity / DoS while still burning time
            passwordHasher.verify("dummy-short-password", dummyHash);
            throw new InvalidCredentialsException("Invalid email or password.");
        }

        String normalizedEmail = normalizeEmail(email);
        var userOptional = userRepository.findByEmail(normalizedEmail);

        if (userOptional.isEmpty()) {
            // User does not exist: run constant-time dummy verification
            passwordHasher.verify(rawPassword, dummyHash);
            throw new InvalidCredentialsException("Invalid email or password.");
        }

        User user = userOptional.get();
        boolean matches = passwordHasher.verify(rawPassword, user.passwordHash());
        if (!matches) {
            throw new InvalidCredentialsException("Invalid email or password.");
        }

        SessionContext session = sessionManager.createSession(user);
        return new AuthResult(user, session);
    }

    private void validateRegistrationInput(String name, String email, String rawPassword) {
        if (name == null || name.strip().isEmpty()) {
            throw new AuthValidationException("Name must not be empty.");
        }
        if (name.length() > 100) {
            throw new AuthValidationException("Name must not exceed 100 characters.");
        }

        if (email == null || email.strip().isEmpty()) {
            throw new AuthValidationException("Email must not be empty.");
        }
        if (email.length() > 254) {
            throw new AuthValidationException("Email must not exceed 254 characters.");
        }
        if (!EMAIL_PATTERN.matcher(email.strip()).matches()) {
            throw new AuthValidationException("Email address is not in a valid format.");
        }

        if (rawPassword == null) {
            throw new AuthValidationException("Password must not be null.");
        }
        if (rawPassword.length() < BCryptPasswordHasher.MIN_LENGTH) {
            throw new AuthValidationException("Password must be at least " + BCryptPasswordHasher.MIN_LENGTH + " characters long.");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > BCryptPasswordHasher.MAX_BYTES) {
            throw new AuthValidationException("Password must not exceed " + BCryptPasswordHasher.MAX_BYTES + " UTF-8 bytes.");
        }
    }

    public static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
