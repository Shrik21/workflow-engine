package com.orchpilot.workflow.exception;

import com.orchpilot.workflow.dto.ApiError;
import com.orchpilot.workflow.sdk.exception.PluginSecurityException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns exceptions into the single {@link ApiError} shape.
 *
 * <p>Status codes are chosen to be actionable rather than merely correct:
 *
 * <ul>
 *   <li>422 for validation failures, so a client can distinguish "your workflow is wrong" from "your request is
 *       malformed", which is 400.</li>
 *   <li>409 for illegal state transitions and lost optimistic-locking races, both of which mean "retry or look at the
 *       current state", not "fix your request".</li>
 *   <li>503 for a plugin that is installed but not loadable, because that is an operational condition the caller can
 *       do nothing about.</li>
 * </ul>
 *
 * <p>Unexpected exceptions log a stack trace and return a generic message. Internal details, class names, SQL, host
 * names, do not belong in an HTTP response body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({WorkflowNotFoundException.class, ExecutionNotFoundException.class,
            PluginNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(WorkflowEngineException ex, HttpServletRequest request) {
        log.debug("404 {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    /**
     * A request body that could not be parsed.
     *
     * <p>Without this the failure falls through to the catch-all and answers 500, which tells the caller the
     * server is broken when in fact their JSON is malformed. It is worth handling explicitly: 500 sends people
     * to read server logs for a problem that is entirely on the client side, and it is exactly what an
     * unquoted shell one-liner produces.
     *
     * <p>Jackson's message names the offending character and position, which is genuinely useful to whoever
     * is building the request, and discloses nothing about the server. The cause chain is not included,
     * because it carries internal type names.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        log.debug("Rejected an unreadable request body on {}: {}", request.getRequestURI(), ex.getMessage());

        String detail = ex.getMostSpecificCause().getMessage();
        return ResponseEntity.badRequest().body(ApiError.of("MALFORMED_REQUEST",
                "The request body could not be parsed as JSON",
                detail == null ? List.of() : List.of(detail.lines().findFirst().orElse(detail)),
                request.getRequestURI()));
    }

    @ExceptionHandler(WorkflowValidationException.class)
    public ResponseEntity<ApiError> handleWorkflowValidation(WorkflowValidationException ex,
                                                            HttpServletRequest request) {
        log.info("Workflow validation rejected {}: {} problem(s)", request.getRequestURI(),
                ex.getErrors().size());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), ex.getErrors(), request.getRequestURI()));
    }

    /**
     * A rejected form submission.
     *
     * <p>422 rather than 400: the request is well formed and the caller understood the contract, but the values
     * do not satisfy the form. A client distinguishes the two — 400 means fix your code, 422 means show these
     * messages to the user.
     */
    @ExceptionHandler(FormSubmissionInvalidException.class)
    public ResponseEntity<ApiError> handleFormSubmission(FormSubmissionInvalidException ex,
                                                        HttpServletRequest request) {
        // Field names only. The values are the user's data and do not belong in a server log.
        log.info("Rejected a form submission on {}: {} field(s) invalid: {}", request.getRequestURI(),
                ex.getProblems().size(), ex.getProblems().keySet());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), ex.getErrors(), request.getRequestURI()));
    }

    @ExceptionHandler(PluginValidationException.class)
    public ResponseEntity<ApiError> handlePluginValidation(PluginValidationException ex,
                                                           HttpServletRequest request) {
        log.warn("Plugin upload rejected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), ex.getErrors(), request.getRequestURI()));
    }

    @ExceptionHandler(PluginLoadException.class)
    public ResponseEntity<ApiError> handlePluginLoad(PluginLoadException ex, HttpServletRequest request) {
        log.error("Plugin load failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    /**
     * The plugin registry could not be reached, or refused.
     *
     * <p>503 rather than the 500 this would otherwise fall through to. A registry this engine cannot reach is a
     * statement about another service's availability, not about a fault here, and the difference decides whether
     * the caller retries or raises a bug.
     */
    @ExceptionHandler(com.orchpilot.workflow.pluginserver.PluginServerUnavailableException.class)
    public ResponseEntity<ApiError> handleRegistryUnavailable(
            com.orchpilot.workflow.pluginserver.PluginServerUnavailableException ex,
            HttpServletRequest request) {
        log.warn("Plugin registry unavailable on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(NodeExecutorNotFoundException.class)
    public ResponseEntity<ApiError> handleMissingExecutor(NodeExecutorNotFoundException ex,
                                                         HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(InvalidWorkflowStateException.class)
    public ResponseEntity<ApiError> handleInvalidState(InvalidWorkflowStateException ex,
                                                      HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    /**
     * A form submit refused because the workflow instance is paused or terminated.
     *
     * <p>409 with the instance-specific error code ({@code WORKFLOW_INSTANCE_PAUSED} /
     * {@code WORKFLOW_INSTANCE_TERMINATED}) so the client can distinguish the two and show the right message.
     * More specific than the {@link WorkflowEngineException} catch-all below, so this handler wins.
     */
    @ExceptionHandler(WorkflowInstanceStateException.class)
    public ResponseEntity<ApiError> handleInstanceState(WorkflowInstanceStateException ex,
                                                       HttpServletRequest request) {
        log.info("Refused a form action on {}: {}", request.getRequestURI(), ex.getErrorCode());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentModification(OptimisticLockingFailureException ex,
                                                                HttpServletRequest request) {
        log.info("Concurrent modification on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("CONCURRENT_MODIFICATION",
                        "Another change was applied first. Re-read the resource and try again.",
                        request.getRequestURI()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateKeyException ex, HttpServletRequest request) {
        log.info("Duplicate key on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("ALREADY_EXISTS", "That resource already exists", request.getRequestURI()));
    }

    @ExceptionHandler(SecretAccessException.class)
    public ResponseEntity<ApiError> handleSecretAccess(SecretAccessException ex, HttpServletRequest request) {
        log.warn("Secret access problem on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(PluginSecurityException.class)
    public ResponseEntity<ApiError> handlePluginSecurity(PluginSecurityException ex,
                                                        HttpServletRequest request) {
        log.warn("Plugin permission denied on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(ExpressionEvaluationException.class)
    public ResponseEntity<ApiError> handleExpression(ExpressionEvaluationException ex,
                                                    HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex,
                                                        HttpServletRequest request) {
        List<String> details = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                details.add(error.getField() + ": " + error.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors().forEach(error ->
                details.add(error.getObjectName() + ": " + error.getDefaultMessage()));
        return ResponseEntity.badRequest()
                .body(ApiError.of("REQUEST_INVALID", "The request body is not valid", details,
                        request.getRequestURI()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleUploadTooLarge(MaxUploadSizeExceededException ex,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of("UPLOAD_TOO_LARGE",
                        "The uploaded file exceeds the configured maximum size", request.getRequestURI()));
    }

    /**
     * A public form request refused: invalid, expired, revoked, already submitted, or the workflow is paused or
     * terminated.
     *
     * <p>Answers the status the state dictates (404 / 410 / 409) and puts the customer-facing state in the error
     * code so the public page can render the right screen from an already-open tab. The message is safe to show
     * a customer and reveals nothing about the workflow.
     */
    @ExceptionHandler(com.orchpilot.workflow.externalform.ExternalFormException.class)
    public ResponseEntity<ApiError> handleExternalForm(
            com.orchpilot.workflow.externalform.ExternalFormException ex, HttpServletRequest request) {
        log.info("External form request refused on {}: {}", request.getRequestURI(), ex.state());
        return ResponseEntity.status(ex.status())
                .body(ApiError.of(ex.state().name(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex,
                                                         HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("REQUEST_INVALID", ex.getMessage(), request.getRequestURI()));
    }

    /**
     * A {@code .orchpilot} file that failed to authenticate, parse or validate on import.
     *
     * <p>400, not 500: a tampered, corrupt, wrong-password or unsupported file is the caller's input, not a
     * server fault. The message is deliberately non-specific — it never reveals whether the tag, the magic, the
     * version or the password was the problem, which would help an attacker probe the format — and nothing about
     * the decrypted contents is logged.
     */
    @ExceptionHandler(com.orchpilot.workflow.portability.PackageIntegrityException.class)
    public ResponseEntity<ApiError> handlePackageIntegrity(
            com.orchpilot.workflow.portability.PackageIntegrityException ex, HttpServletRequest request) {
        log.info("Rejected an import file on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiError.of("IMPORT_FILE_INVALID", ex.getMessage(), request.getRequestURI()));
    }

    /**
     * File storage failures carry their own status, because they are not all server faults: an unconfigured
     * storage root is the administrator's outstanding task, a missing file is a 404, and a rejected path is a
     * bad request. Declared before the {@link WorkflowEngineException} handler it would otherwise match, since
     * that one answers 500 for everything.
     */
    @ExceptionHandler(com.orchpilot.workflow.storage.exception.FileStorageException.class)
    public ResponseEntity<ApiError> handleFileStorage(
            com.orchpilot.workflow.storage.exception.FileStorageException ex, HttpServletRequest request) {
        if (ex.getStatus().is5xxServerError()) {
            log.error("Storage failure on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        } else {
            log.warn("Storage request refused on {}: {} ({})", request.getRequestURI(), ex.getMessage(),
                    ex.getErrorCode());
        }
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(WorkflowEngineException.class)
    public ResponseEntity<ApiError> handleEngine(WorkflowEngineException ex, HttpServletRequest request) {
        log.error("Engine error on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.internalServerError()
                .body(ApiError.of(ex.getErrorCode(), ex.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Deliberately generic: the stack trace goes to the log, not to the client.
        log.error("Unhandled {} on {}", ex.getClass().getName(), request.getRequestURI(), ex);
        return ResponseEntity.internalServerError()
                .body(ApiError.of("INTERNAL_ERROR", "The request could not be completed",
                        request.getRequestURI()));
    }
}
