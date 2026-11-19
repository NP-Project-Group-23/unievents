package com.unievents.unievents_backend.domain.repository;

import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.model.User;
import com.unievents.unievents_backend.domain.service.DuplicateEmailException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * JDBC-backed implementation of {@link UserRepository} using Spring's {@link JdbcTemplate}.
 * Handles PostgreSQL / H2 identity keys, case-insensitive email normalization, and constraint mappings.
 */
@Repository
public class JdbcUserRepository implements UserRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<User> userRowMapper = (rs, rowNum) -> {
        Long id = rs.getLong("id");
        String name = rs.getString("name");
        String email = rs.getString("email");
        String passwordHash = rs.getString("password_hash");

        String roleStr = null;
        try {
            roleStr = rs.getString("role");
        } catch (SQLException ignored) {
            // Field might be omitted in non-standard queries
        }
        Role role = Role.fromString(roleStr);

        Timestamp ts = null;
        try {
            ts = rs.getTimestamp("created_at");
        } catch (SQLException ignored) {
        }
        Instant createdAt = ts != null ? ts.toInstant() : Instant.now();

        return new User(id, name, email, passwordHash, role, createdAt);
    };

    public JdbcUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @jakarta.annotation.PostConstruct
    public void ensureSchema() {
        try {
            jdbcTemplate.execute("ALTER TABLE app_users ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'STUDENT'");
        } catch (Exception ignored) {
            // Safe fallback if table does not exist yet or running in custom H2 mock
        }
    }

    @Override
    public User save(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null");
        }
        String normalizedEmail = normalizeEmail(user.email());

        if (user.id() == null) {
            // New user registration
            KeyHolder keyHolder = new GeneratedKeyHolder();
            try {
                jdbcTemplate.update(connection -> {
                    PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO app_users(name, email, password_hash, role) VALUES (?, ?, ?, ?)",
                            java.sql.Statement.RETURN_GENERATED_KEYS
                    );
                    ps.setString(1, user.name() != null ? user.name().strip() : "");
                    ps.setString(2, normalizedEmail);
                    ps.setString(3, user.passwordHash());
                    ps.setString(4, user.role() != null ? user.role().name() : Role.STUDENT.name());
                    return ps;
                }, keyHolder);
            } catch (DuplicateKeyException ex) {
                throw new DuplicateEmailException("An account with email " + normalizedEmail + " already exists.");
            } catch (DataIntegrityViolationException ex) {
                if (ex.getMessage() != null && (ex.getMessage().toLowerCase().contains("unique") || ex.getMessage().toLowerCase().contains("duplicate"))) {
                    throw new DuplicateEmailException("An account with email " + normalizedEmail + " already exists.");
                }
                throw ex;
            }

            Long generatedId = null;
            java.util.Map<String, Object> keys = keyHolder.getKeys();
            if (keys != null) {
                Object idObj = keys.get("ID");
                if (idObj == null) {
                    idObj = keys.get("id");
                }
                if (idObj instanceof Number n) {
                    generatedId = n.longValue();
                }
            }
            if (generatedId == null) {
                try {
                    Number k = keyHolder.getKey();
                    if (k != null) {
                        generatedId = k.longValue();
                    }
                } catch (Exception ignored) {}
            }

            if (generatedId != null) {
                final Long finalId = generatedId;
                return findById(finalId).orElseGet(() -> user.withId(finalId));
            }
            return user;
        } else {
            // Update existing user
            try {
                jdbcTemplate.update(
                        "UPDATE app_users SET name = ?, email = ?, password_hash = ?, role = ? WHERE id = ?",
                        user.name() != null ? user.name().strip() : "",
                        normalizedEmail,
                        user.passwordHash(),
                        user.role() != null ? user.role().name() : Role.STUDENT.name(),
                        user.id()
                );
            } catch (DuplicateKeyException ex) {
                throw new DuplicateEmailException("An account with email " + normalizedEmail + " already exists.");
            } catch (DataIntegrityViolationException ex) {
                if (ex.getMessage() != null && (ex.getMessage().toLowerCase().contains("unique") || ex.getMessage().toLowerCase().contains("duplicate"))) {
                    throw new DuplicateEmailException("An account with email " + normalizedEmail + " already exists.");
                }
                throw ex;
            }
            return findById(user.id()).orElse(user);
        }
    }

    @Override
    public Optional<User> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            User user = jdbcTemplate.queryForObject(
                    "SELECT id, name, email, password_hash, role, created_at FROM app_users WHERE id = ?",
                    userRowMapper,
                    id
            );
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<User> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        String normalizedEmail = normalizeEmail(email);
        List<User> results = jdbcTemplate.query(
                "SELECT id, name, email, password_hash, role, created_at FROM app_users WHERE LOWER(email) = ?",
                userRowMapper,
                normalizedEmail
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    @Override
    public boolean existsByEmail(String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String normalizedEmail = normalizeEmail(email);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_users WHERE LOWER(email) = ?",
                Integer.class,
                normalizedEmail
        );
        return count != null && count > 0;
    }

    @Override
    public void deleteById(Long id) {
        if (id == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM app_users WHERE id = ?", id);
    }

    public static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
