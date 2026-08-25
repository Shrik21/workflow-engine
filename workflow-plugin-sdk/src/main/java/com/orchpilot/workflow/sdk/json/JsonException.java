package com.orchpilot.workflow.sdk.json;

/**
 * Thrown when {@link Json} cannot read or write a value.
 *
 * @since 1.0.0
 */
public class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
