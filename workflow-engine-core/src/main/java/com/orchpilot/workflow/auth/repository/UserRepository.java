package com.orchpilot.workflow.auth.repository;

import com.orchpilot.workflow.auth.model.Role;
import com.orchpilot.workflow.auth.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Optional;

/**
 * Users.
 *
 * <p>Lookups are by the already-lower-cased {@code username} or {@code email}, which is how
 * case-insensitive uniqueness is achieved without a case-insensitive collation: normalising on write
 * keeps the unique index doing the work and keeps queries index-covered.
 */
public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /**
     * @param role role to look for
     * @return whether any account holds it, used to decide whether to bootstrap an administrator
     */
    boolean existsByRolesContaining(Role role);

    /**
     * Case-insensitive search across username, email and name, for the admin user list.
     *
     * @param term     regular-expression-escaped search term
     * @param pageable page request
     * @return matching users
     */
    @Query("{ $or: [ { 'username': { $regex: ?0, $options: 'i' } }, "
            + "{ 'email': { $regex: ?0, $options: 'i' } }, "
            + "{ 'firstName': { $regex: ?0, $options: 'i' } }, "
            + "{ 'lastName': { $regex: ?0, $options: 'i' } } ] }")
    Page<User> search(String term, Pageable pageable);

    Page<User> findByRolesContaining(Role role, Pageable pageable);
}
