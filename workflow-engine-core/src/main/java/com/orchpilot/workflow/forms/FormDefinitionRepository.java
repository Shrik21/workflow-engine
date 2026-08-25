package com.orchpilot.workflow.forms;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * The editable form heads.
 *
 * <p>A top-level interface, not a nested one. Spring Data's repository scan does not create proxies for
 * interfaces declared inside a container class, so grouping these to save a file produces a context that
 * fails to start with "required a bean of type ... that could not be found".
 */
public interface FormDefinitionRepository extends MongoRepository<FormDefinition, String> {

    Page<FormDefinition> findByStatus(FormStatus status, Pageable pageable);

    Page<FormDefinition> findByNameContainingIgnoreCase(String name, Pageable pageable);

    boolean existsByName(String name);

    List<FormDefinition> findByOwnerId(String ownerId);

    /**
     * Forms a workflow node can actually reference, for the picker.
     *
     * <p>The predicate is "has a published version and is not archived", not "status is PUBLISHED", and the
     * difference is not pedantry. Editing a published form returns its head to DRAFT while the published
     * snapshot stays intact and in use — that is the whole point of the versioning — so filtering on
     * {@code status == PUBLISHED} would hide a perfectly usable form from the moment somebody opened it in the
     * designer, and un-hide it when they published again. What decides whether a node can run is whether a
     * snapshot exists for it to render.
     *
     * <p>Archived forms are excluded because they are retired: existing nodes referencing one keep working,
     * which is why archiving is not deletion, but it should not be offered for a new one.
     *
     * @param excluded the status to leave out, {@link FormStatus#ARCHIVED}
     * @return selectable forms, by name
     */
    List<FormDefinition> findByPublishedVersionNotNullAndStatusNotOrderByNameAsc(FormStatus excluded);
}
