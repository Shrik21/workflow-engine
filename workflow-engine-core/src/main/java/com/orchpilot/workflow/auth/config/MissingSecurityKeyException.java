package com.orchpilot.workflow.auth.config;

/**
 * A required cryptographic key is missing or unusable.
 *
 * <p>A dedicated type rather than a bare {@link IllegalStateException} so that
 * {@link MissingSecurityKeyFailureAnalyzer} can recognise it precisely and print a short, actionable
 * message instead of two hundred lines of bean-creation stack trace. Matching on an exception message
 * would work until someone reworded the message.
 *
 * <p>The application deliberately refuses to start rather than falling back to a built-in key. A shipped
 * default signing key is indistinguishable from having no authentication at all, and it would be present in
 * every deployment that forgot to override it.
 */
public class MissingSecurityKeyException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final String property;
    private final String environmentVariable;
    private final String generateCommand;
    private final String reason;

    /**
     * @param property            configuration property that is missing or invalid
     * @param environmentVariable environment variable that supplies it
     * @param generateCommand     shell command that produces a suitable value
     * @param reason              what specifically is wrong, for the description line
     */
    public MissingSecurityKeyException(String property, String environmentVariable,
                                      String generateCommand, String reason) {
        super(reason + " Set " + property + " (" + environmentVariable + ").");
        this.property = property;
        this.environmentVariable = environmentVariable;
        this.generateCommand = generateCommand;
        this.reason = reason;
    }

    public String getProperty() {
        return property;
    }

    public String getEnvironmentVariable() {
        return environmentVariable;
    }

    public String getGenerateCommand() {
        return generateCommand;
    }

    public String getReason() {
        return reason;
    }
}
