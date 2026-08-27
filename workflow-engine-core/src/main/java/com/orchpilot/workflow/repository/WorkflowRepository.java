package com.orchpilot.workflow.repository;

import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Access to workflow definition heads.
 */
@Repository
public interface WorkflowRepository extends MongoRepository<Workflow, String> {

    Page<Workflow> findByStatus(WorkflowStatus status, Pageable pageable);

    Page<Workflow> findByNameContainingIgnoreCase(String name, Pageable pageable);

    List<Workflow> findByStatusAndTriggersType(WorkflowStatus status, com.orchpilot.workflow.model.TriggerType type);

    /** Workflows a group is attached to, used to detach it cleanly when the group is deleted. */
    List<Workflow> findByAccessGroupsContaining(String groupId);

    /**
     * The workflows one user may see: those they own, plus those shared with a group they belong to.
     *
     * <p>Filtering in the query rather than after fetching is what makes this data isolation rather than
     * presentation. Fetching everything and hiding rows afterwards leaks through totals, paging and any
     * future endpoint that forgets to apply the filter.
     *
     * @param ownerId  the user's id
     * @param groupIds the user's group ids; an empty list simply matches nothing
     * @param pageable page request
     * @return the accessible workflows
     */
    @org.springframework.data.mongodb.repository.Query(
            "{ $or: [ { 'ownerId': ?0 }, { 'accessGroups': { $in: ?1 } } ] }")
    Page<Workflow> findAccessible(String ownerId, java.util.Collection<String> groupIds, Pageable pageable);

    /** As {@link #findAccessible}, narrowed to one status. */
    @org.springframework.data.mongodb.repository.Query(
            "{ 'status': ?2, $or: [ { 'ownerId': ?0 }, { 'accessGroups': { $in: ?1 } } ] }")
    Page<Workflow> findAccessibleByStatus(String ownerId, java.util.Collection<String> groupIds,
                                          WorkflowStatus status, Pageable pageable);

    boolean existsByNameIgnoreCase(String name);

    /**
     * Workflows with at least one node backed by a given plugin, whether they pin a version or not.
     *
     * <p>Asked before a plugin version is removed. The match is deliberately wide: a node may name the plugin
     * through {@code pluginId}, or name one of its node types directly, and both keep running because of it. The
     * caller narrows the result node by node, because {@code $elemMatch} answering "some node mentions this
     * plugin" is exactly the question worth asking in the database and the wrong place to decide which node it
     * was.
     *
     * @param status    normally {@code PUBLISHED}: a draft that would break is the author's problem, a published
     *                  workflow that would break is the platform's
     * @param pluginId  the plugin
     * @param nodeTypes node types the plugin contributes; an empty collection simply matches nothing
     * @return the workflows that mention it
     */
    @org.springframework.data.mongodb.repository.Query(
            "{ 'status': ?0, 'nodes': { $elemMatch: { $or: [ { 'pluginId': ?1 }, { 'type': { $in: ?2 } } ] } } }")
    List<Workflow> findUsingPlugin(WorkflowStatus status, String pluginId,
                                   java.util.Collection<String> nodeTypes);
}
