package com.unievents.unievents_backend;

import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.model.User;
import com.unievents.unievents_backend.domain.repository.JdbcUserRepository;
import com.unievents.unievents_backend.domain.service.DuplicateEmailException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UserRepositoryTest {

    private SingleConnectionDataSource dataSource;
    private JdbcUserRepository repository;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        String dbName = "user_repo_test_" + UUID.randomUUID().toString().replace("-", "");
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:" + dbName + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSource.setSuppressClose(true);

        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            CREATE TABLE app_users (
                id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                email VARCHAR(254) NOT NULL UNIQUE,
                password_hash VARCHAR(255) NOT NULL,
                role VARCHAR(20) NOT NULL DEFAULT 'STUDENT',
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                CONSTRAINT ck_users_role CHECK (role IN ('STUDENT', 'ADMIN'))
            );
            CREATE INDEX idx_users_email ON app_users(email);
        """);

        repository = new JdbcUserRepository(jdbcTemplate);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.destroy();
        }
    }

    @Test
    @DisplayName("save persists user and generates auto-increment ID")
    void testSaveAndFindById() {
        User user = new User(null, "Alice Student", "alice@uni.edu", "$2a$12$dummyHashValue", Role.STUDENT, Instant.now());
        User saved = repository.save(user);

        assertNotNull(saved.id(), "Generated ID should not be null");
        assertTrue(saved.id() > 0, "Generated ID should be positive");
        assertEquals("Alice Student", saved.name());
        assertEquals("alice@uni.edu", saved.email());
        assertEquals(Role.STUDENT, saved.role());
        assertNotNull(saved.createdAt());

        Optional<User> fetched = repository.findById(saved.id());
        assertTrue(fetched.isPresent());
        assertEquals(saved.id(), fetched.get().id());
        assertEquals("alice@uni.edu", fetched.get().email());
        assertEquals(Role.STUDENT, fetched.get().role());
    }

    @Test
    @DisplayName("findByEmail handles whitespace and case normalization")
    void testFindByEmailWithNormalization() {
        User user = new User(null, "Bob Smith", "bob.smith@uni.edu", "$2a$12$hash", Role.STUDENT, Instant.now());
        repository.save(user);

        Optional<User> direct = repository.findByEmail("bob.smith@uni.edu");
        assertTrue(direct.isPresent());

        Optional<User> upperCased = repository.findByEmail("  BOB.SMITH@UNI.EDU  ");
        assertTrue(upperCased.isPresent());
        assertEquals("bob.smith@uni.edu", upperCased.get().email());
    }

    @Test
    @DisplayName("duplicate email registration throws DuplicateEmailException")
    void testDuplicateEmailThrowsException() {
        User user1 = new User(null, "Charlie", "charlie@uni.edu", "$2a$12$hash1", Role.STUDENT, Instant.now());
        repository.save(user1);

        User user2 = new User(null, "Charlie Duplicate", "CHARLIE@UNI.EDU", "$2a$12$hash2", Role.STUDENT, Instant.now());
        assertThrows(DuplicateEmailException.class, () -> repository.save(user2));
    }

    @Test
    @DisplayName("existsByEmail correctly detects present and missing emails")
    void testExistsByEmail() {
        assertFalse(repository.existsByEmail("nobody@uni.edu"));

        repository.save(new User(null, "Dave", "dave@uni.edu", "$2a$12$hash", Role.STUDENT, Instant.now()));

        assertTrue(repository.existsByEmail("dave@uni.edu"));
        assertTrue(repository.existsByEmail("  DAVE@UNI.EDU "));
        assertFalse(repository.existsByEmail("other@uni.edu"));
        assertFalse(repository.existsByEmail(""));
        assertFalse(repository.existsByEmail(null));
    }

    @Test
    @DisplayName("role column correctly maps STUDENT and ADMIN roles")
    void testRoleMappingStudentAndAdmin() {
        User student = repository.save(new User(null, "Student User", "student@uni.edu", "$2a$12$h1", Role.STUDENT, Instant.now()));
        User admin = repository.save(new User(null, "Admin User", "admin@uni.edu", "$2a$12$h2", Role.ADMIN, Instant.now()));

        User fetchedStudent = repository.findById(student.id()).orElseThrow();
        assertEquals(Role.STUDENT, fetchedStudent.role());

        User fetchedAdmin = repository.findById(admin.id()).orElseThrow();
        assertEquals(Role.ADMIN, fetchedAdmin.role());
    }

    @Test
    @DisplayName("update existing user modifies fields and preserves ID")
    void testUpdateExistingUser() {
        User initial = repository.save(new User(null, "Eve Initial", "eve@uni.edu", "$2a$12$h1", Role.STUDENT, Instant.now()));
        User toUpdate = new User(initial.id(), "Eve Updated", "eve.updated@uni.edu", "$2a$12$h2", Role.ADMIN, initial.createdAt());

        User updated = repository.save(toUpdate);
        assertEquals(initial.id(), updated.id());
        assertEquals("Eve Updated", updated.name());
        assertEquals("eve.updated@uni.edu", updated.email());
        assertEquals(Role.ADMIN, updated.role());

        User fetched = repository.findById(initial.id()).orElseThrow();
        assertEquals("Eve Updated", fetched.name());
        assertEquals("eve.updated@uni.edu", fetched.email());
        assertEquals(Role.ADMIN, fetched.role());
    }

    @Test
    @DisplayName("deleteById removes user from database")
    void testDeleteById() {
        User user = repository.save(new User(null, "Frank", "frank@uni.edu", "$2a$12$h", Role.STUDENT, Instant.now()));
        assertTrue(repository.findById(user.id()).isPresent());

        repository.deleteById(user.id());
        assertFalse(repository.findById(user.id()).isPresent());
        assertFalse(repository.existsByEmail("frank@uni.edu"));
    }

    @Test
    @DisplayName("querying non-existent user returns empty Optional")
    void testNonExistentUserReturnsEmpty() {
        assertTrue(repository.findById(99999L).isEmpty());
        assertTrue(repository.findByEmail("nonexistent@uni.edu").isEmpty());
    }
}
