package com.unievents.unievents_backend.domain.repository;

import com.unievents.unievents_backend.domain.model.User;

import java.util.Optional;

/**
 * Repository interface for managing {@link User} persistence.
 */
public interface UserRepository {

    /**
     * Persists a new or existing user entity.
     *
     * @param user user entity to save
     * @return persisted user entity with generated ID populated
     */
    User save(User user);

    /**
     * Finds a user by primary key ID.
     *
     * @param id user ID
     * @return optional containing the user if found
     */
    Optional<User> findById(Long id);

    /**
     * Finds a user by email address (case-insensitive normalized).
     *
     * @param email user email address
     * @return optional containing the user if found
     */
    Optional<User> findByEmail(String email);

    /**
     * Checks if a user already exists with the given email address.
     *
     * @param email user email address
     * @return true if an account exists, false otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Deletes a user by primary key ID.
     *
     * @param id user ID
     */
    void deleteById(Long id);
}
