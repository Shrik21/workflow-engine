package com.orchpilot.workflow.auth.controller;

import com.orchpilot.workflow.auth.service.AuthenticationException;
import com.orchpilot.workflow.auth.service.DuplicateAccountException;
import com.orchpilot.workflow.auth.service.OperationNotAllowedException;
import com.orchpilot.workflow.auth.service.PasswordPolicyException;
import com.orchpilot.workflow.dto.ApiError;
import com.orchpilot.workflow.encryption.EncryptionException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Maps authentication failures to HTTP responses, in the API's single error shape.
 *
 * <p>Ordered ahead of the engine's general handler so these specific types are not swallowed by a broader
 * rule and turned into a 500.
 *
 * <p>The controlling principle is that a response says what the caller needs to act on and nothing more.
 * A failed login is always 401 with the same text; the actual reason went to the audit log. An access
 * denial names no rule. An encryption failure does not echo the value involved.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    /**
     * Any authentication failure.
     *
     * <p>Always 401, always the exception's generic message. The specific reason is deliberately not
     * included: distinguishing an unknown user from a wrong password would let an attacker enumerate
     * accounts without guessing a single password.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> onAuthenticationFailure(AuthenticationException ex,
                                                            HttpServletRequest request) {
        // Recorded here at debug only. The audit trail already holds the reason, attributed and queryable.
        log.debug("Authentication failed at {}: {}", request.getRequestURI(), ex.getReason());
        String code = ex.isLockedOut() ? "ACCOUNT_TEMPORARILY_LOCKED" : "UNAUTHORIZED";
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header("Cache-Control", "no-store")
                .body(ApiError.of(code, ex.getMessage(), request.getRequestURI()));
    }

    /**
     * A weak or unconfirmed password.
     *
     * <p>422 rather than 400: the request was well formed and understood, and was refused on a business
     * rule. Every violation is listed so the user can fix them in one attempt.
     */
    @ExceptionHandler(PasswordPolicyException.class)
    public ResponseEntity<ApiError> onPasswordPolicy(PasswordPolicyException ex, HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiError.of("PASSWORD_POLICY_VIOLATION", ex.getMessage(),
                        ex.getViolations(), request.getRequestURI()));
    }

    /**
     * A refusal the server made deliberately: removing the last administrator, an administrator acting on
     * their own account, or registration being switched off.
     *
     * <p>Carries its own status because these differ in kind, and the message is written to be shown to the
     * caller. Previously these were {@code IllegalStateException} and answered 500, which reported a server
     * fault for a decision the server made on purpose and threw away the explanation.
     */
    @ExceptionHandler(OperationNotAllowedException.class)
    public ResponseEntity<ApiError> onOperationNotAllowed(OperationNotAllowedException ex,
                                                         HttpServletRequest request) {
        log.info("Refused {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of("OPERATION_NOT_ALLOWED", ex.getMessage(), request.getRequestURI()));
    }

    /** A taken username or email. */
    @ExceptionHandler(DuplicateAccountException.class)
    public ResponseEntity<ApiError> onDuplicate(DuplicateAccountException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("ACCOUNT_EXISTS", ex.getMessage(),
                        List.of(ex.getField()), request.getRequestURI()));
    }

    /**
     * Authorization refused inside a method, from {@code @PreAuthorize}.
     *
     * <p>The filter-chain equivalent is handled by {@code SecurityErrorResponder}; this covers denials
     * raised after a request has already reached a controller, so both produce the same 403 body.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> onAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.info("Access denied at {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "You do not have permission to perform this action",
                        request.getRequestURI()));
    }

    /**
     * Encryption or decryption failed.
     *
     * <p>The message is safe to return: {@link EncryptionException} is written never to include the value
     * involved, only the failure category, such as a missing key or a failed authentication tag.
     */
    @ExceptionHandler(EncryptionException.class)
    public ResponseEntity<ApiError> onEncryption(EncryptionException ex, HttpServletRequest request) {
        log.error("Secret encryption failure at {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiError.of("ENCRYPTION_UNAVAILABLE", ex.getMessage(), request.getRequestURI()));
    }
}
