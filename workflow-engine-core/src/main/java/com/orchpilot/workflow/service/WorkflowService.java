package com.orchpilot.workflow.service;

import com.orchpilot.workflow.dto.ValidationResponse;
import com.orchpilot.workflow.dto.WorkflowRequest;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowStatus;
import com.orchpilot.workflow.model.WorkflowVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Authoring and publishing of workflow definitions.
 */
public interface WorkflowService {

    /**
     * @param request workflow definition
     * @param actor   who is creating it
     * @return the created workflow, in DRAFT
     */
    Workflow create(WorkflowRequest request, String actor);

    /**
     * Replaces a workflow's definition.
     *
     * <p>Editing a published workflow is allowed and moves it back to DRAFT. The published version it was
     * snapshot into is untouched, so running executions are unaffected and the workflow stays executable at
     * its last published version until the new definition is published.
     *
     * @param id      workflow id
     * @param request new definition
     * @param actor   who is changing it
     * @return the updated workflow
     */
    Workflow update(String id, WorkflowRequest request, String actor);

    /**
     * @param id workflow id
     * @return the workflow
     * @throws com.orchpilot.workflow.exception.WorkflowNotFoundException when absent
     */
    Workflow get(String id);

    /**
     * @param status   optional status filter
     * @param name     optional name substring filter
     * @param pageable paging
     * @return a page of workflows
     */
    Page<Workflow> list(WorkflowStatus status, String name, Pageable pageable);

    /**
     * Lists only the workflows the calling user may view.
     *
     * <p>Separate from {@link #list} rather than replacing it, because the engine itself still needs the
     * unfiltered view: the scheduler and the event dispatcher act with no user and must see every workflow
     * with a matching trigger. Making the filtered form the one the REST layer calls, and leaving the
     * unfiltered form for engine-internal use, keeps that distinction explicit rather than implicit in
     * whether a security context happens to be bound.
     *
     * @param status   optional status filter
     * @param name     optional name substring
     * @param pageable page request
     * @return the accessible workflows
     */
    Page<Workflow> listAccessible(WorkflowStatus status, String name, Pageable pageable);

    /**
     * @param id    workflow id
     * @param actor who is deleting it
     */
    void delete(String id, String actor);

    /**
     * Validates and snapshots the definition into an immutable version, and reconciles its triggers.
     *
     * @param id    workflow id
     * @param actor who is publishing
     * @return the published version
     * @throws com.orchpilot.workflow.exception.WorkflowValidationException when the definition is not publishable
     */
    WorkflowVersion publish(String id, String actor);

    /**
     * @param id workflow id
     * @return validation errors and warnings without changing anything
     */
    ValidationResponse validate(String id);

    /**
     * Archives a workflow: existing executions continue, no new ones start, triggers are removed.
     *
     * @param id    workflow id
     * @param actor who is archiving it
     * @return the archived workflow
     */
    Workflow archive(String id, String actor);

    /**
     * @param id workflow id
     * @return the currently published version
     * @throws com.orchpilot.workflow.exception.InvalidWorkflowStateException when it has never been published
     */
    WorkflowVersion requirePublishedVersion(String id);

    /**
     * @param id      workflow id
     * @param version version number
     * @return that exact version
     * @throws com.orchpilot.workflow.exception.WorkflowNotFoundException when absent
     */
    WorkflowVersion requireVersion(String id, int version);
}
