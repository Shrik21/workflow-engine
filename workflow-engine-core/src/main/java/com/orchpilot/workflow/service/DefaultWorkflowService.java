package com.orchpilot.workflow.service;

import com.orchpilot.workflow.dto.ValidationResponse;
import com.orchpilot.workflow.dto.WorkflowRequest;
import com.orchpilot.workflow.exception.InvalidWorkflowStateException;
import com.orchpilot.workflow.exception.WorkflowNotFoundException;
import com.orchpilot.workflow.exception.WorkflowValidationException;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowStatus;
import com.orchpilot.workflow.model.WorkflowVersion;
import com.orchpilot.workflow.repository.WorkflowRepository;
import com.orchpilot.workflow.repository.WorkflowVersionRepository;
import com.orchpilot.workflow.scheduler.WorkflowScheduleService;
import com.orchpilot.workflow.utility.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Workflow authoring.
 *
 * <p>The important decision here is that <b>publishing snapshots</b>. A published workflow is copied into an
 * immutable {@link WorkflowVersion} and executions pin that version number. Editing the workflow afterwards
 * moves it back to DRAFT but changes nothing that is running, and the previously published version stays
 * executable until a new one is published. Without this, editing a workflow while a form has been open for two
 * days would resume that execution against a graph whose nodes no longer exist.
 *
 * <p>Republishing identical content reuses the existing version instead of creating a duplicate, detected by
 * fingerprinting the definition. Otherwise clicking publish twice would produce two identical versions and make
 * the version history useless for spotting real changes.
 */
@Service
public class DefaultWorkflowService implements WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowService.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository versionRepository;
    private final WorkflowValidator validator;
    private final WorkflowDtoMapper mapper;
    private final WorkflowScheduleService scheduleService;
    private final AuditService auditService;

    /**
     * Ownership and tenant checks.
     *
     * <p>Consulted here rather than in the controller because a workflow is reached by more paths than a
     * controller annotation covers: update, delete, publish and archive all modify a definition, and each
     * would otherwise need its own annotation to be equally protected.
     */
    private final WorkflowAccessPolicy accessPolicy;

    /** Group-based authorization, used to scope the workflow list to what the caller may view. */
    private final com.orchpilot.workflow.access.WorkflowAuthorizationService authorization;

    private final com.orchpilot.workflow.scheduler.SchedulerExpressionBuilder scheduleBuilder;

    public DefaultWorkflowService(WorkflowRepository workflowRepository,
                                  WorkflowVersionRepository versionRepository, WorkflowValidator validator,
                                  WorkflowDtoMapper mapper, WorkflowScheduleService scheduleService,
                                  AuditService auditService, WorkflowAccessPolicy accessPolicy,
                                  com.orchpilot.workflow.access.WorkflowAuthorizationService authorization,
                                  com.orchpilot.workflow.scheduler.SchedulerExpressionBuilder scheduleBuilder) {
        this.workflowRepository = workflowRepository;
        this.versionRepository = versionRepository;
        this.validator = validator;
        this.mapper = mapper;
        this.scheduleService = scheduleService;
        this.auditService = auditService;
        this.accessPolicy = accessPolicy;
        this.authorization = authorization;
        this.scheduleBuilder = scheduleBuilder;
    }

    @Override
    public Workflow create(WorkflowRequest request, String actor) {
        Workflow workflow = new Workflow();
        workflow.setId(UUID.randomUUID().toString());
        workflow.setStatus(WorkflowStatus.DRAFT);
        workflow.setVersion(1);
        workflow.setCreatedAt(Instant.now());
        workflow.setCreatedBy(actor);
        // Records the creator as owner, which is what later grants them edit, publish and delete rights
        // without an administrator having to be involved.
        accessPolicy.stampOwnership(workflow);
        apply(workflow, request, actor);
        Workflow saved = workflowRepository.save(workflow);
        auditService.record(actor, "WORKFLOW_CREATED", "WORKFLOW", saved.getId(), "OK",
                java.util.Map.of("name", String.valueOf(saved.getName()), "nodes", saved.getNodes().size()));
        log.info("Created workflow {} '{}' with {} node(s)", saved.getId(), saved.getName(),
                saved.getNodes().size());
        return saved;
    }

    @Override
    public Workflow update(String id, WorkflowRequest request, String actor) {
        Workflow workflow = get(id);
        accessPolicy.requireEdit(workflow);
        if (workflow.getStatus() == WorkflowStatus.ARCHIVED) {
            throw new InvalidWorkflowStateException("Workflow '" + id
                    + "' is archived and cannot be edited. Create a new workflow instead.");
        }
        accessPolicy.stampUpdate(workflow);
        apply(workflow, request, actor);
        if (workflow.getStatus() == WorkflowStatus.PUBLISHED) {
            // Back to DRAFT: the published snapshot stays executable, so this is a safe, reversible edit.
            workflow.setStatus(WorkflowStatus.DRAFT);
            log.info("Workflow {} moved back to DRAFT after an edit; version {} remains published", id,
                    workflow.getPublishedVersion());
        }
        Workflow saved = workflowRepository.save(workflow);
        auditService.record(actor, "WORKFLOW_UPDATED", "WORKFLOW", id, "OK", null);
        return saved;
    }

    @Override
    public Workflow get(String id) {
        return workflowRepository.findById(id).orElseThrow(() -> new WorkflowNotFoundException(id));
    }

    @Override
    public Page<Workflow> list(WorkflowStatus status, String name, Pageable pageable) {
        if (status != null) {
            return workflowRepository.findByStatus(status, pageable);
        }
        if (name != null && !name.isBlank()) {
            return workflowRepository.findByNameContainingIgnoreCase(name.trim(), pageable);
        }
        return workflowRepository.findAll(pageable);
    }

    @Override
    public Page<Workflow> listAccessible(WorkflowStatus status, String name, Pageable pageable) {
        var scope = authorization.visibleWorkflowScope(
                com.orchpilot.workflow.auth.security.CurrentUser.userId().orElse(null));

        if (scope.unrestricted()) {
            return list(status, name, pageable);
        }
        if (scope.isEmpty()) {
            // Nothing owned and no group grants view: answer an empty page without querying at all.
            return org.springframework.data.domain.Page.empty(pageable);
        }

        Page<Workflow> accessible = status != null
                ? workflowRepository.findAccessibleByStatus(scope.ownerId(), scope.groupIds(), status, pageable)
                : workflowRepository.findAccessible(scope.ownerId(), scope.groupIds(), pageable);

        if (name == null || name.isBlank()) {
            return accessible;
        }
        // The name filter is applied to the already-restricted page rather than pushed into the query.
        // Combining a regex with the access predicate would need a hand-written aggregation, and this
        // narrows a result the user is already entitled to see, so it cannot widen access.
        String needle = name.trim().toLowerCase(Locale.ROOT);
        java.util.List<Workflow> matching = accessible.getContent().stream()
                .filter(workflow -> workflow.getName() != null
                        && workflow.getName().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
        return new org.springframework.data.domain.PageImpl<>(matching, pageable, matching.size());
    }

    @Override
    public void delete(String id, String actor) {
        Workflow workflow = get(id);
        accessPolicy.requireDelete(workflow);
        scheduleService.removeSchedules(id);
        versionRepository.deleteByWorkflowId(id);
        workflowRepository.delete(workflow);
        auditService.record(actor, "WORKFLOW_DELETED", "WORKFLOW", id, "OK", null);
        log.info("Deleted workflow {} and its versions", id);
    }

    @Override
    public WorkflowVersion publish(String id, String actor) {
        Workflow workflow = get(id);
        // Publishing makes a definition executable by everyone, so it is an ownership-gated action rather
        // than something any editor can do to any workflow.
        accessPolicy.requirePublish(workflow);
        List<String> errors = validator.validate(workflow);
        if (!errors.isEmpty()) {
            auditService.record(actor, "WORKFLOW_PUBLISH_REJECTED", "WORKFLOW", id, "FAILED",
                    java.util.Map.of("errors", errors));
            throw new WorkflowValidationException(id, errors);
        }

        String fingerprint = fingerprint(workflow);
        WorkflowVersion existing = versionRepository.findFirstByWorkflowIdOrderByVersionDesc(id).orElse(null);
        if (existing != null && fingerprint.equals(existing.getDefinitionHash())) {
            // Identical content: reuse the version rather than manufacturing a duplicate.
            workflow.setStatus(WorkflowStatus.PUBLISHED);
            workflow.setPublishedVersion(existing.getVersion());
            workflow.setPublishedAt(Instant.now());
            workflow.setUpdatedAt(Instant.now());
            workflow.setUpdatedBy(actor);
            workflowRepository.save(workflow);
            scheduleService.reconcile(workflow, existing.getVersion());
            log.info("Workflow {} republished unchanged; version {} reused", id, existing.getVersion());
            return existing;
        }

        int nextVersion = existing == null ? 1 : existing.getVersion() + 1;
        WorkflowVersion version = new WorkflowVersion();
        version.setId(WorkflowVersion.idFor(id, nextVersion));
        version.setWorkflowId(id);
        version.setVersion(nextVersion);
        version.setName(workflow.getName());
        version.setDescription(workflow.getDescription());
        version.setNodes(new ArrayList<>(workflow.getNodes()));
        version.setConnections(new ArrayList<>(workflow.getConnections()));
        version.setVariables(new LinkedHashMap<>(workflow.getVariables()));
        version.setTriggers(new ArrayList<>(workflow.getTriggers()));
        version.setDefinitionHash(fingerprint);
        version.setPublishedAt(Instant.now());
        version.setPublishedBy(actor);
        WorkflowVersion savedVersion = versionRepository.save(version);

        workflow.setStatus(WorkflowStatus.PUBLISHED);
        workflow.setPublishedVersion(nextVersion);
        workflow.setVersion(nextVersion + 1);
        workflow.setPublishedAt(Instant.now());
        workflow.setUpdatedAt(Instant.now());
        workflow.setUpdatedBy(actor);
        workflowRepository.save(workflow);

        scheduleService.reconcile(workflow, nextVersion);

        List<String> warnings = validator.warnings(workflow);
        auditService.record(actor, "WORKFLOW_PUBLISHED", "WORKFLOW", id, "OK",
                java.util.Map.of("version", nextVersion, "warnings", warnings));
        log.info("Published workflow {} as version {}{}", id, nextVersion,
                warnings.isEmpty() ? "" : " with " + warnings.size() + " warning(s)");
        return savedVersion;
    }

    @Override
    public ValidationResponse validate(String id) {
        Workflow workflow = get(id);
        return ValidationResponse.of(validator.validate(workflow), validator.warnings(workflow));
    }

    @Override
    public Workflow archive(String id, String actor) {
        Workflow workflow = get(id);
        workflow.setStatus(WorkflowStatus.ARCHIVED);
        workflow.setUpdatedAt(Instant.now());
        workflow.setUpdatedBy(actor);
        scheduleService.removeSchedules(id);
        Workflow saved = workflowRepository.save(workflow);
        auditService.record(actor, "WORKFLOW_ARCHIVED", "WORKFLOW", id, "OK", null);
        log.info("Archived workflow {}", id);
        return saved;
    }

    @Override
    public WorkflowVersion requirePublishedVersion(String id) {
        Workflow workflow = get(id);
        if (workflow.getPublishedVersion() == null) {
            throw new InvalidWorkflowStateException("Workflow '" + id + "' has never been published. "
                    + "Publish it with POST /api/workflows/" + id + "/publish before executing it.");
        }
        if (workflow.getStatus() == WorkflowStatus.ARCHIVED) {
            throw new InvalidWorkflowStateException("Workflow '" + id
                    + "' is archived and cannot start new executions");
        }
        return requireVersion(id, workflow.getPublishedVersion());
    }

    @Override
    public WorkflowVersion requireVersion(String id, int version) {
        return versionRepository.findByWorkflowIdAndVersion(id, version)
                .orElseThrow(() -> new WorkflowNotFoundException(id, version));
    }

    // ---------------------------------------------------------------- helpers

    private void apply(Workflow workflow, WorkflowRequest request, String actor) {
        workflow.setName(request.name());
        workflow.setDescription(request.description());
        workflow.setNodes(mapper.toNodes(request.nodes()));
        workflow.setConnections(mapper.toConnections(request.connections()));
        workflow.setTriggers(mapper.toTriggers(request.triggers()));
        generateScheduleCrons(workflow);
        workflow.setVariables(request.variables() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.variables()));
        workflow.setMetadata(request.metadata() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.metadata()));
        workflow.setUpdatedAt(Instant.now());
        workflow.setUpdatedBy(actor);
    }

    /**
     * Regenerates each schedule trigger's cron from its friendly configuration, so the cron the scheduler runs
     * is always what the operator's dropdowns say — the browser never invents it. A legacy trigger that carries
     * a raw cron and no configuration is left untouched, so nothing built before the friendly builder breaks.
     */
    private void generateScheduleCrons(Workflow workflow) {
        for (com.orchpilot.workflow.model.WorkflowTrigger trigger : workflow.getTriggers()) {
            if (trigger.getType() == com.orchpilot.workflow.model.TriggerType.SCHEDULE
                    && trigger.getSchedule() != null && trigger.getSchedule().getFrequency() != null) {
                trigger.setCron(scheduleBuilder.buildCron(trigger.getSchedule()));
            }
        }
    }

    /**
     * Fingerprints the executable parts of a definition.
     *
     * <p>Name, description and metadata are deliberately excluded: renaming a workflow does not change what it
     * does, and it should not manufacture a new version.
     */
    private String fingerprint(Workflow workflow) {
        List<Object> material = new ArrayList<>();
        for (var node : workflow.getNodes()) {
            material.add(java.util.Map.of(
                    "id", String.valueOf(node.getId()),
                    "type", String.valueOf(node.getType()),
                    "plugin", String.valueOf(node.getPluginId()) + ":" + node.getPluginVersion(),
                    "configuration", node.getConfiguration(),
                    "inputMapping", node.getInputMapping(),
                    "outputMapping", node.getOutputMapping(),
                    "conditions", node.getConditions().stream()
                            .map(condition -> condition.getBranch() + "=" + condition.getExpression())
                            .toList(),
                    "defaultBranch", String.valueOf(node.getDefaultBranch()),
                    "errorPolicy", String.valueOf(node.effectiveErrorPolicy())));
        }
        for (var connection : workflow.getConnections()) {
            material.add(connection.getSource() + "|" + connection.getSourcePort() + "|"
                    + connection.getTarget() + "|" + connection.getCondition());
        }
        material.add(workflow.getVariables());
        for (var trigger : workflow.getTriggers()) {
            material.add(trigger.getId() + "|" + trigger.getType() + "|" + trigger.getCron() + "|"
                    + trigger.getEventName() + "|" + trigger.isEnabled());
        }
        return HashUtils.fingerprint(material);
    }
}
