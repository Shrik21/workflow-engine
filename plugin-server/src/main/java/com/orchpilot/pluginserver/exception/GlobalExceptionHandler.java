package com.orchpilot.pluginserver.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns exceptions into {@link ApiError}.
 *
 * <p>The catch-all deliberately says nothing. A registry's stack traces name internal classes, storage layout
 * and occasionally file paths; the log is the place for those, and the response is not.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PluginServerException.class)
    public ResponseEntity<ApiError> handleKnown(PluginServerException ex, HttpServletRequest request) {
        // Client mistakes are routine and logged quietly; a 5xx from this family is not and is not.
        if (ex.getStatus().is5xxServerError()) {
            log.error("{} on {}: {}", ex.getCode(), request.getRequestURI(), ex.getMessage(), ex);
        } else {
            log.info("{} on {}: {}", ex.getCode(), request.getRequestURI(), ex.getMessage());
        }
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getCode(), ex.getMessage(), ex.getDetails(), request.getRequestURI()));
    }

    /**
     * A losing race on the unique key of a plugin version.
     *
     * <p>Two administrators uploading the same version at the same moment. The insert arbitrates, and the loser
     * gets the same answer as if they had been second by an hour, which is the point of keying the collection on
     * {@code pluginId:version} rather than checking first.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> handleDuplicate(DuplicateKeyException ex, HttpServletRequest request) {
        log.info("Duplicate key on {}: {}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("PLUGIN_VERSION_ALREADY_EXISTS",
                        "That plugin version already exists. Published versions are immutable.",
                        request.getRequestURI()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex,
                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of("PLUGIN_ARCHIVE_TOO_LARGE",
                        "The uploaded archive exceeds the configured maximum size.",
                        request.getRequestURI()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBeanValidation(MethodArgumentNotValidException ex,
                                                        HttpServletRequest request) {
        List<String> details = new ArrayList<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> details.add(error.getField() + ": " + error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ApiError.of("REQUEST_INVALID",
                "The request body is not valid.", details, request.getRequestURI()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex,
                                                     HttpServletRequest request) {
        String detail = ex.getMostSpecificCause().getMessage();
        return ResponseEntity.badRequest().body(ApiError.of("MALFORMED_REQUEST",
                "The request body could not be parsed as JSON.",
                detail == null ? List.of() : List.of(detail.lines().findFirst().orElse(detail)),
                request.getRequestURI()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex,
                                                          HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("REQUEST_INVALID", ex.getMessage(), request.getRequestURI()));
    }

    /**
     * A method-security denial.
     *
     * <p>{@code @PreAuthorize} throws this from inside the controller, which is past the filter chain, so the
     * {@code AccessDeniedHandler} configured on the chain never sees it and the catch-all below turned it into a
     * 500. That reports a server fault for a decision the server made deliberately, and hides the 403 a client
     * needs in order to tell "I may not do this" from "this is broken".
     *
     * <p>The message is fixed rather than the exception's, for the same reason as in the chain's handler: naming
     * the authority that would have worked is a hint about a system the caller is not entitled to.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.info("Refused {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("FORBIDDEN",
                "You do not have permission to perform this action on the plugin registry.",
                request.getRequestURI()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex,
                                                        HttpServletRequest request) {
        log.info("Unauthenticated {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiError.of("UNAUTHORIZED",
                "Authentication is required to access the plugin registry.", request.getRequestURI()));
    }

    /**
     * The framework's own 4xx, kept at the status it chose.
     *
     * <p>Without this the catch-all below turns "no such endpoint" into 500, which is how a client asking for a
     * path that does not exist gets told the registry is broken. Every Spring MVC exception worth reporting,
     * including {@code NoResourceFoundException} for an unmapped path and
     * {@code HttpRequestMethodNotSupportedException} for the wrong verb, implements {@link ErrorResponse} and
     * already knows its own status.
     *
     * <p>The message is the exception's own only for a 4xx. A 5xx {@code ErrorResponse} falls through to the
     * generic wording, because those messages can describe internals.
     */
    @ExceptionHandler({ErrorResponseException.class, NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ApiError> handleFrameworkError(Exception ex, HttpServletRequest request) {
        // Every type listed above implements ErrorResponse, which is an interface and so cannot itself be
        // named in @ExceptionHandler: that argument must be a Throwable type.
        ErrorResponse error = (ErrorResponse) ex;
        HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
        log.info("{} on {} {}", status.value(), request.getMethod(), request.getRequestURI());

        String code = status == HttpStatus.NOT_FOUND ? "NOT_FOUND" : status.name();
        String message = status.is4xxClientError()
                ? error.getBody().getDetail()
                : "The request could not be completed.";
        return ResponseEntity.status(status).body(ApiError.of(code,
                message == null ? status.getReasonPhrase() : message, request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled {} on {}", ex.getClass().getName(), request.getRequestURI(), ex);
        return ResponseEntity.internalServerError().body(ApiError.of("INTERNAL_ERROR",
                "The request could not be completed.", request.getRequestURI()));
    }
}
