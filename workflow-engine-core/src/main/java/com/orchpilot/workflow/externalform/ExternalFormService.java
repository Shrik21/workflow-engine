package com.orchpilot.workflow.externalform;

import com.orchpilot.workflow.auth.service.OperationNotAllowedException;
import com.orchpilot.workflow.exception.FormSubmissionInvalidException;
import com.orchpilot.workflow.execution.ExecutionStateStore;
import com.orchpilot.workflow.forms.FormNodeBinding;
import com.orchpilot.workflow.forms.FormVersion;
import com.orchpilot.workflow.model.ExecutionStatus;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.task.HumanTask;
import com.orchpilot.workflow.task.HumanTaskRepository;
import com.orchpilot.workflow.task.HumanTaskService;
import com.orchpilot.workflow.task.TaskCompletionService;
import com.orchpilot.workflow.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Year;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Opens, drafts and submits a form for an external customer, authorised only by a secure link.
 *
 * <h2>Everything is resolved from the token</h2>
 *
 * The customer supplies a token and, on submit, field values by name. The task, the workflow instance and the
 * tenant all come from the token record; nothing about which task or tenant is being touched is taken from the
 * request, so there is nothing for a customer to tamper with. The same server-side form validation and the same
 * {@code submitSignal} engine-resume the internal task path uses are reused here — an external submission is an
 * internal completion with a different front door, not a second form system.
 *
 * <h2>The instance's state is the gate, checked on every call</h2>
 *
 * Open computes a customer-safe {@link ExternalFormState} from the task and instance. Draft is allowed whenever
 * the task still permits it — including while the instance is paused or terminated, so a customer never loses
 * input. Submit is refused, with a 409 and a clear message, unless the instance is running and the task is still
 * actionable, so an already-open browser tab cannot push a submission onto a paused, terminated or finished
 * instance.
 */
@Service
public class ExternalFormService {

    private static final Logger log = LoggerFactory.getLogger(ExternalFormService.class);

    private final ExternalFormTokenService tokenService;
    private final HumanTaskRepository taskRepository;
    private final ExecutionStateStore executionStore;
    private final FormNodeBinding binding;
    private final TaskCompletionService completion;
    private final HumanTaskService taskService;
    private final AuditService audit;

    public ExternalFormService(ExternalFormTokenService tokenService, HumanTaskRepository taskRepository,
                               ExecutionStateStore executionStore, FormNodeBinding binding,
                               TaskCompletionService completion, HumanTaskService taskService,
                               AuditService audit) {
        this.tokenService = tokenService;
        this.taskRepository = taskRepository;
        this.executionStore = executionStore;
        this.binding = binding;
        this.completion = completion;
        this.taskService = taskService;
        this.audit = audit;
    }

    /** The confirmation an external submission returns. */
    public record SubmitResult(String referenceNumber) {
    }

    /**
     * Renders the form (or the reason it cannot be filled in) for a link.
     *
     * @param rawToken the token from the URL
     * @param clientIp caller IP, for the audit trail
     * @param userAgent caller user-agent, for the audit trail
     * @return the view to render
     */
    public PublicFormView open(String rawToken, String clientIp, String userAgent) {
        ExternalFormAccessToken token;
        try {
            token = tokenService.resolve(rawToken);
        } catch (ExternalFormException ex) {
            // A completely unknown token is a 404 for the controller; the renderable dead states (expired,
            // revoked, used) become a page rather than an error.
            if (ex.state() == ExternalFormState.INVALID) {
                throw ex;
            }
            return PublicFormView.of(ex.state(), ex.getMessage());
        }

        HumanTask task = taskRepository.findById(token.getTaskId()).orElseThrow(ExternalFormException::invalid);
        ExecutionStatus instanceStatus = executionStore.currentStatus(token.getWorkflowInstanceId())
                .orElse(null);
        ExternalFormState state = state(task, instanceStatus);

        audit(token, "EXTERNAL_FORM_OPENED", clientIp, userAgent, Map.of("state", state.name()));

        if (state == ExternalFormState.ALREADY_SUBMITTED || state == ExternalFormState.CANCELLED) {
            return PublicFormView.of(state, messageFor(state));
        }

        Optional<FormVersion> form = binding.resolve(task.getFormDefinitionId(),
                task.getFormVersion() > 0 ? task.getFormVersion() : null);
        if (form.isEmpty()) {
            // A task whose form cannot be resolved cannot be shown to a customer; treat it as unavailable
            // rather than rendering an empty form.
            return PublicFormView.of(ExternalFormState.INVALID, "This form is not available.");
        }

        boolean allowSubmit = state == ExternalFormState.OPEN && token.isAllowSubmit()
                && task.getStatus().isActionable();
        boolean allowDraft = token.isAllowDraft() && task.getStatus().allowsDraft();
        return PublicFormView.fillable(state, messageFor(state), form.get(), allowSubmit, allowDraft,
                token.getExpiresAt(), initialData(task));
    }

    /**
     * Saves a draft against the link's token.
     *
     * @param rawToken  the token from the URL
     * @param formData  the partial values
     * @param clientIp  caller IP, for the audit trail
     * @param userAgent caller user-agent, for the audit trail
     */
    public void saveDraft(String rawToken, Map<String, Object> formData, String clientIp, String userAgent) {
        ExternalFormAccessToken token = tokenService.resolve(rawToken);
        HumanTask task = taskRepository.findById(token.getTaskId()).orElseThrow(ExternalFormException::invalid);
        if (!token.isAllowDraft() || !task.getStatus().allowsDraft()) {
            throw ExternalFormException.alreadySubmitted();
        }
        taskService.saveDraftExternally(token.getTaskId(), formData, "external");
        audit(token, "EXTERNAL_FORM_DRAFT_SAVED", clientIp, userAgent,
                Map.of("fields", formData == null ? java.util.List.of()
                        : new java.util.ArrayList<>(formData.keySet())));
    }

    /**
     * Submits the form, completing the task and continuing the workflow.
     *
     * @param rawToken  the token from the URL
     * @param formData  the submitted values
     * @param clientIp  caller IP, for the audit trail
     * @param userAgent caller user-agent, for the audit trail
     * @return a reference number for the customer
     */
    public SubmitResult submit(String rawToken, Map<String, Object> formData, String clientIp,
                               String userAgent) {
        ExternalFormAccessToken token = tokenService.resolve(rawToken);
        HumanTask task = taskRepository.findById(token.getTaskId()).orElseThrow(ExternalFormException::invalid);
        ExecutionStatus instanceStatus = executionStore.currentStatus(token.getWorkflowInstanceId())
                .orElseThrow(ExternalFormException::cancelled);

        // The instance-state gate, with customer-safe messages and the right 409/410 statuses.
        ExternalFormState state = state(task, instanceStatus);
        if (state != ExternalFormState.OPEN) {
            audit(token, "EXTERNAL_FORM_SUBMIT_FAILED", clientIp, userAgent, Map.of("reason", state.name()));
            throw exceptionFor(state);
        }

        try {
            // Reuses the internal completion primitive: server-side validation, task completion and the same
            // engine resume. Its actionable re-check arbitrates a terminate landing at the same moment.
            completion.completeExternally(token.getTaskId(), formData, "external");
        } catch (FormSubmissionInvalidException ex) {
            audit(token, "EXTERNAL_FORM_SUBMIT_FAILED", clientIp, userAgent, Map.of("reason", "validation"));
            throw ex; // 422, handled by the global handler
        } catch (OperationNotAllowedException ex) {
            audit(token, "EXTERNAL_FORM_SUBMIT_FAILED", clientIp, userAgent, Map.of("reason", "conflict"));
            throw ExternalFormException.alreadySubmitted();
        }

        tokenService.recordSubmission(token);
        String reference = referenceNumber(token);
        audit(token, "EXTERNAL_FORM_SUBMITTED", clientIp, userAgent, Map.of("reference", reference));
        log.info("External form submitted for task {} (instance {})", token.getTaskId(),
                token.getWorkflowInstanceId());
        return new SubmitResult(reference);
    }

    // ------------------------------------------------------------------ internals

    /** The customer-facing state, computed from the task and the instance — never exposing either directly. */
    private ExternalFormState state(HumanTask task, ExecutionStatus instanceStatus) {
        TaskStatus taskStatus = task.getStatus();
        if (taskStatus == TaskStatus.COMPLETED) {
            return ExternalFormState.ALREADY_SUBMITTED;
        }
        if (taskStatus == TaskStatus.TERMINATED) {
            return ExternalFormState.WORKFLOW_TERMINATED;
        }
        if (taskStatus == TaskStatus.CANCELLED || taskStatus == TaskStatus.EXPIRED) {
            return ExternalFormState.CANCELLED;
        }
        if (instanceStatus == null) {
            return ExternalFormState.CANCELLED;
        }
        return switch (instanceStatus) {
            case PAUSED -> ExternalFormState.WORKFLOW_PAUSED;
            case TERMINATED -> ExternalFormState.WORKFLOW_TERMINATED;
            case COMPLETED -> ExternalFormState.ALREADY_SUBMITTED;
            case CANCELLED, FAILED -> ExternalFormState.CANCELLED;
            case RUNNING, WAITING, PENDING -> ExternalFormState.OPEN;
        };
    }

    private static ExternalFormException exceptionFor(ExternalFormState state) {
        return switch (state) {
            case WORKFLOW_PAUSED -> ExternalFormException.paused();
            case WORKFLOW_TERMINATED -> ExternalFormException.terminated();
            case ALREADY_SUBMITTED -> ExternalFormException.alreadySubmitted();
            case EXPIRED -> ExternalFormException.expired();
            case REVOKED -> ExternalFormException.revoked();
            default -> ExternalFormException.cancelled();
        };
    }

    private static String messageFor(ExternalFormState state) {
        return switch (state) {
            case OPEN -> null;
            case WORKFLOW_PAUSED -> ExternalFormException.paused().getMessage();
            case WORKFLOW_TERMINATED -> ExternalFormException.terminated().getMessage();
            case ALREADY_SUBMITTED -> "This form has already been submitted.";
            case EXPIRED -> "This form link has expired.";
            case REVOKED -> "This form link has been revoked.";
            case CANCELLED -> "This form is no longer available.";
            case INVALID -> "This form link is not valid.";
        };
    }

    /** Draft values over the prefill, so a returning customer sees what they last saved. */
    private Map<String, Object> initialData(HumanTask task) {
        Map<String, Object> initial = new LinkedHashMap<>();
        if (task.getPrefill() != null) {
            initial.putAll(task.getPrefill());
        }
        if (task.getDraftData() != null) {
            initial.putAll(task.getDraftData());
        }
        return initial;
    }

    /** A short, non-sensitive confirmation reference derived from the instance, never exposing the id itself. */
    private String referenceNumber(ExternalFormAccessToken token) {
        int suffix = Math.abs(java.util.Objects.hash(token.getWorkflowInstanceId(), token.getId())) % 1_000_000;
        return "OP-" + Year.now() + "-" + String.format("%06d", suffix);
    }

    private void audit(ExternalFormAccessToken token, String action, String clientIp, String userAgent,
                       Map<String, Object> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("taskId", token.getTaskId());
        details.put("workflowInstanceId", token.getWorkflowInstanceId());
        details.put("tenantId", token.getTenantId());
        if (clientIp != null) {
            details.put("ip", clientIp);
        }
        if (userAgent != null) {
            details.put("userAgent", userAgent);
        }
        details.putAll(extra);
        audit.record("external", action, "WORKFLOW_INSTANCE", token.getWorkflowInstanceId(), "OK", details);
    }
}
