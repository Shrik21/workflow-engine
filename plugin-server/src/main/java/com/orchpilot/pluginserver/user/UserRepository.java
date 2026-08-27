package com.orchpilot.pluginserver.user;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Accounts on this registry.
 *
 * <p>A top-level interface. Spring Data does not create proxies for repository interfaces nested inside a
 * container class, and this platform has already lost a startup to discovering that.
 */
public interface UserRepository extends MongoRepository<User, String> {

    /** Case-insensitive: usernames are compared as people type them, not as they were stored. */
    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    List<User> findAllByOrderByUsernameAsc();

    /** Used to decide whether the bootstrap administrator is needed, and to refuse the last admin's removal. */
    List<User> findByRolesContaining(String role);

    long countByRolesContainingAndEnabledTrue(String role);
}
