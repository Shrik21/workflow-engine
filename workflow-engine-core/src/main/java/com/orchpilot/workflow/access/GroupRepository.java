package com.orchpilot.workflow.access;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Groups. */
public interface GroupRepository extends MongoRepository<Group, String> {

    Optional<Group> findByName(String name);

    boolean existsByName(String name);

    List<Group> findByEnabledTrueOrderByNameAsc();

    /**
     * @param ids group ids, typically the intersection of a user's groups and a workflow's
     * @return the enabled ones; disabled groups grant nothing and are filtered here rather than by callers
     */
    List<Group> findByIdInAndEnabledTrue(Collection<String> ids);

    Page<Group> findByNameContainingIgnoreCaseOrDescriptionContainingIgnoreCase(
            String name, String description, Pageable pageable);
}
