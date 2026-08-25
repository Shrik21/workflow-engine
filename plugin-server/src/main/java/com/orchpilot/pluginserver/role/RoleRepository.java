package com.orchpilot.pluginserver.role;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/** Roles. A top-level interface, for the reason given on {@code UserRepository}. */
public interface RoleRepository extends MongoRepository<Role, String> {

    Optional<Role> findByName(String name);

    boolean existsByName(String name);

    List<Role> findAllByOrderByNameAsc();
}
