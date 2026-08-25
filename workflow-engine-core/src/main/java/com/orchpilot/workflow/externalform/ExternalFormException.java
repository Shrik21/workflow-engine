package com.orchpilot.workflow.externalform;

import org.springframework.http.HttpStatus;

/**
 * A public form request refused, carrying the customer-facing {@link ExternalFormState} that names why.
 *
 * <p>Thrown by the draft and submit paths so an already-open browser tab gets a precise, safe rejection — the
 * right HTTP status and a message that reveals nothing about the workflow behind the form. The state is what the
 * public page turns into its "Link expired" / "Already submitted" / "Workflow paused" screens.
 */
public class ExternalFormException extends RuntimeException {

    private final ExternalFormState state;

    public ExternalFormException(ExternalFormState state, String message) {
        super(message);
        this.state = state;
    }

    public ExternalFormState state() {
        return state;
    }

    /** The HTTP status this state answers with. */
    public HttpStatus status() {
        return switch (state) {
            case INVALID -> HttpStatus.NOT_FOUND;
            case EXPIRED, REVOKED -> HttpStatus.GONE;
            case ALREADY_SUBMITTED, CANCELLED, WORKFLOW_PAUSED, WORKFLOW_TERMINATED -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    public static ExternalFormException invalid() {
        return new ExternalFormException(ExternalFormState.INVALID, "This form link is not valid.");
    }

    public static ExternalFormException expired() {
        return new ExternalFormException(ExternalFormState.EXPIRED, "This form link has expired.");
    }

    public static ExternalFormException revoked() {
        return new ExternalFormException(ExternalFormState.REVOKED, "This form link has been revoked.");
    }

    public static ExternalFormException alreadySubmitted() {
        return new ExternalFormException(ExternalFormState.ALREADY_SUBMITTED,
                "This form has already been submitted.");
    }

    public static ExternalFormException paused() {
        return new ExternalFormException(ExternalFormState.WORKFLOW_PAUSED,
                "This workflow is currently paused. You can save your progress but cannot submit the form "
                        + "until the workflow is resumed.");
    }

    public static ExternalFormException terminated() {
        return new ExternalFormException(ExternalFormState.WORKFLOW_TERMINATED,
                "This workflow instance has been terminated. This form can no longer be submitted.");
    }

    public static ExternalFormException cancelled() {
        return new ExternalFormException(ExternalFormState.CANCELLED, "This form is no longer available.");
    }
}
