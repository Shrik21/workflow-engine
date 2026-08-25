package com.orchpilot.workflow.externalform;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.repository.WorkflowRepository;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.task.HumanTask;
import com.orchpilot.workflow.task.HumanTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Mints, resolves, revokes and regenerates the hashed access tokens behind external form links.
 *
 * <h2>The raw token crosses this boundary exactly once</h2>
 *
 * {@link #create} and {@link #regenerate} return the raw token so a link can be built and shown to the operator
 * that one time; nothing else ever returns it, and it is never stored — only its hash is. {@link #resolve}
 * takes a raw token from a customer's URL, hashes it, and looks the record up by hash, applying every state and
 * expiry rule before handing back a usable token.
 */
@Service
public class ExternalFormTokenService {

    private static final Logger log = LoggerFactory.getLogger(ExternalFormTokenService.class);

    private final SecureTokenGenerator generator;
    private final ExternalFormAccessTokenRepository tokens;
    private final HumanTaskRepository taskRepository;
    private final WorkflowRepository workflowRepository;
    private final AuditService audit;
    private final WorkflowEngineProperties properties;

    public ExternalFormTokenService(SecureTokenGenerator generator,
                                    ExternalFormAccessTokenRepository tokens,
                                    HumanTaskRepository taskRepository, WorkflowRepository workflowRepository,
                                    AuditService audit, WorkflowEngineProperties properties) {
        this.generator = generator;
        this.tokens = tokens;
        this.taskRepository = taskRepository;
        this.workflowRepository = workflowRepository;
        this.audit = audit;
        this.properties = properties;
    }

    /** What an operator chose when generating a link. */
    public record CreateOptions(Duration expiresIn, int maxSubmissions, boolean allowSubmit, boolean allowDraft,
                                String customerName, String customerEmail, String customerReference) {

        /** Defaults matching the specification: single-use, 24h (from config), submit and draft allowed. */
        public static CreateOptions defaults(Duration expiresIn) {
            return new CreateOptions(expiresIn, 1, true, true, null, null, null);
        }
    }

    /** The one time the raw token is exposed, alongside its stored record. */
    public record GeneratedLink(String rawToken, ExternalFormAccessToken token) {
    }

    /**
     * Mints a link for a task.
     *
     * @param taskId  the external form task
     * @param options link settings
     * @param actor   the internal user generating it
     * @return the raw token (shown once) and the stored record
     */
    public GeneratedLink create(String taskId, CreateOptions options, String actor) {
        HumanTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("No task with id '" + taskId + "'"));

        String rawToken = generator.newRawToken();
        Instant now = Instant.now();
        Duration ttl = options.expiresIn() != null ? options.expiresIn()
                : Duration.ofHours(properties.getExternalForm().getDefaultExpirationHours());

        ExternalFormAccessToken token = new ExternalFormAccessToken();
        token.setTokenHash(generator.hash(rawToken));
        token.setTaskId(taskId);
        token.setWorkflowInstanceId(task.getWorkflowExecutionId());
        token.setTenantId(tenantOf(task));
        token.setStatus(ExternalFormTokenStatus.ACTIVE);
        token.setExpiresAt(now.plus(ttl));
        token.setMaxSubmissions(Math.max(1, options.maxSubmissions()));
        token.setSubmissionCount(0);
        token.setAllowSubmit(options.allowSubmit());
        token.setAllowDraft(options.allowDraft());
        token.setCreatedAt(now);
        token.setCreatedBy(actor);
        token.setCustomerName(options.customerName());
        token.setCustomerEmail(options.customerEmail());
        token.setCustomerReference(options.customerReference());
        ExternalFormAccessToken saved = tokens.save(token);

        audit.record(actor, "EXTERNAL_FORM_LINK_CREATED", "WORKFLOW_INSTANCE", token.getWorkflowInstanceId(),
                "OK", auditDetails(saved, Map.of("expiresAt", String.valueOf(saved.getExpiresAt()),
                        "maxSubmissions", saved.getMaxSubmissions())));
        log.info("External form link created for task {} by {} (expires {})", taskId, actor,
                saved.getExpiresAt());
        return new GeneratedLink(rawToken, saved);
    }

    /**
     * Resolves and validates a raw token from a customer URL.
     *
     * @param rawToken the token from the URL
     * @return the active token
     * @throws ExternalFormException with the state to show when the link is invalid, expired, revoked or used
     */
    public ExternalFormAccessToken resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ExternalFormException.invalid();
        }
        ExternalFormAccessToken token = tokens.findByTokenHash(generator.hash(rawToken))
                .orElseThrow(ExternalFormException::invalid);

        switch (token.getStatus()) {
            case REVOKED -> throw ExternalFormException.revoked();
            case EXPIRED -> throw ExternalFormException.expired();
            case USED -> throw ExternalFormException.alreadySubmitted();
            case ACTIVE -> { /* fall through to the expiry check */ }
        }
        if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(Instant.now())) {
            // Lazily flip to EXPIRED so the state is durable and audited, then refuse.
            token.setStatus(ExternalFormTokenStatus.EXPIRED);
            tokens.save(token);
            audit.record("external", "EXTERNAL_FORM_EXPIRED", "WORKFLOW_INSTANCE",
                    token.getWorkflowInstanceId(), "OK", auditDetails(token, Map.of()));
            throw ExternalFormException.expired();
        }
        return token;
    }

    /** Records a successful submission, retiring the token when its allowance is spent. */
    public void recordSubmission(ExternalFormAccessToken token) {
        token.setSubmissionCount(token.getSubmissionCount() + 1);
        if (token.getSubmissionCount() >= token.getMaxSubmissions()) {
            token.setStatus(ExternalFormTokenStatus.USED);
            token.setUsedAt(Instant.now());
        }
        tokens.save(token);
    }

    /**
     * Revokes every active link for a task. The URLs stop working immediately.
     *
     * @return how many links were revoked
     */
    public int revoke(String taskId, String actor) {
        List<ExternalFormAccessToken> active =
                tokens.findByTaskIdAndStatus(taskId, ExternalFormTokenStatus.ACTIVE);
        Instant now = Instant.now();
        for (ExternalFormAccessToken token : active) {
            token.setStatus(ExternalFormTokenStatus.REVOKED);
            token.setRevokedAt(now);
            token.setRevokedBy(actor);
            tokens.save(token);
            audit.record(actor, "EXTERNAL_FORM_LINK_REVOKED", "WORKFLOW_INSTANCE",
                    token.getWorkflowInstanceId(), "OK", auditDetails(token, Map.of()));
        }
        if (!active.isEmpty()) {
            log.info("Revoked {} external form link(s) for task {} by {}", active.size(), taskId, actor);
        }
        return active.size();
    }

    /** Revokes the current link(s) and mints a fresh one. The old URL is dead the moment the new one exists. */
    public GeneratedLink regenerate(String taskId, CreateOptions options, String actor) {
        revoke(taskId, actor);
        GeneratedLink link = create(taskId, options, actor);
        audit.record(actor, "EXTERNAL_FORM_LINK_REGENERATED", "WORKFLOW_INSTANCE",
                link.token().getWorkflowInstanceId(), "OK", auditDetails(link.token(), Map.of()));
        return link;
    }

    /** The tokens minted for a task, newest first, for the management view. */
    public List<ExternalFormAccessToken> forTask(String taskId) {
        return tokens.findByTaskIdOrderByCreatedAtDesc(taskId);
    }

    private String tenantOf(HumanTask task) {
        if (task.getWorkflowId() == null) {
            return null;
        }
        return workflowRepository.findById(task.getWorkflowId())
                .map(com.orchpilot.workflow.model.Workflow::getTenantId)
                .orElse(null);
    }

    /** Audit detail without any secret: the task and instance ids and whatever extra the caller passes. */
    private static Map<String, Object> auditDetails(ExternalFormAccessToken token, Map<String, Object> extra) {
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("taskId", token.getTaskId());
        details.put("workflowInstanceId", token.getWorkflowInstanceId());
        details.put("tenantId", token.getTenantId());
        details.putAll(extra);
        return details;
    }
}
