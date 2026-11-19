package com.unievents.unievents_backend.config;

import com.unievents.unievents_backend.domain.model.Role;
import com.unievents.unievents_backend.domain.model.User;
import com.unievents.unievents_backend.domain.repository.UserRepository;
import com.unievents.unievents_backend.domain.service.PasswordHasher;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Creates a demo administrator only in the explicitly selected local profile. */
@Component
@Profile("local")
public class LocalDemoBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LocalDemoBootstrap.class);
    private final UserRepository users;
    private final PasswordHasher passwords;

    public LocalDemoBootstrap(UserRepository users, PasswordHasher passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = value("LOCAL_ADMIN_EMAIL", "admin@unievents.local").toLowerCase();
        String password = value("LOCAL_ADMIN_PASSWORD", "Admin123!");
        if (!users.existsByEmail(email)) {
            users.save(new User(null, "Local Administrator", email, passwords.hash(password), Role.ADMIN, Instant.now()));
            log.warn("Created LOCAL-ONLY demo administrator {}. Change LOCAL_ADMIN_PASSWORD outside classroom demos.", email);
        }
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
