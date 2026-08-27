package com.orchpilot.workflow.plugins.email;

import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * One node's email settings, resolved and ready to send.
 *
 * <h2>Built once, at execution, from resolved values</h2>
 *
 * Every text field passes through the engine's own variable resolver as it is read, so
 * {@code ${customer.email}} in the designer is an address by the time anything here inspects it. The plugin
 * does not implement templating: there is one variable system on this platform and it belongs to the engine.
 *
 * <h2>The password is never part of this configuration</h2>
 *
 * What the workflow stores is a <em>name</em> — a secret to look up, or a credential id whose parts are looked
 * up. The value is fetched from the engine's scoped secret provider at execution, held for the length of one
 * send, and registered for redaction by the provider itself. A password typed into a workflow definition would
 * be readable by anybody who can read the workflow, would travel in every export, and would sit in the
 * execution record; none of that is fixed by encrypting the field, which is why there is no field to type it
 * into.
 */
final class EmailNodeConfiguration {

    /**
     * Deliberately permissive.
     *
     * <p>Enough to catch a missing {@code @} or a stray space — the mistakes worth catching before opening a
     * connection. Real address syntax is far broader than most validators admit, and a node that refuses a
     * valid address is a worse failure than one that lets the server refuse an invalid one.
     */
    private static final Pattern ADDRESS = Pattern.compile("[^@\\s,;]+@[^@\\s,;]+\\.[^@\\s,;]+");

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;

    private final EmailProvider provider;
    private final String host;
    private final int port;
    private final SmtpSecurity security;
    private final boolean authenticationRequired;
    private final String username;
    private final String password;
    private final String fromEmail;
    private final String fromName;
    private final List<String> to;
    private final List<String> cc;
    private final List<String> bcc;
    private final String replyTo;
    private final String subject;
    private final EmailBodyType bodyType;
    private final String body;
    private final List<EmailAttachment> attachments;
    private final int connectionTimeoutMillis;
    private final int readTimeoutMillis;

    private EmailNodeConfiguration(Builder builder) {
        this.provider = builder.provider;
        this.host = builder.host;
        this.port = builder.port;
        this.security = builder.security;
        this.authenticationRequired = builder.authenticationRequired;
        this.username = builder.username;
        this.password = builder.password;
        this.fromEmail = builder.fromEmail;
        this.fromName = builder.fromName;
        this.to = List.copyOf(builder.to);
        this.cc = List.copyOf(builder.cc);
        this.bcc = List.copyOf(builder.bcc);
        this.replyTo = builder.replyTo;
        this.subject = builder.subject;
        this.bodyType = builder.bodyType;
        this.body = builder.body;
        this.attachments = List.copyOf(builder.attachments);
        this.connectionTimeoutMillis = builder.connectionTimeoutMillis;
        this.readTimeoutMillis = builder.readTimeoutMillis;
    }

    /**
     * Reads a node's configuration.
     *
     * @param configuration the raw node configuration
     * @param resolve       the engine's variable resolver
     * @param secrets       the scoped secret provider, consulted for the password
     * @return the resolved settings
     * @throws PluginConfigurationException when something required is missing or impossible
     */
    @SuppressWarnings("unchecked")
    static EmailNodeConfiguration from(NodeConfiguration configuration, UnaryOperator<String> resolve,
                                       SecretProvider secrets) {
        Builder builder = new Builder();

        builder.provider = EmailProvider.parse(configuration.getString("provider", "CUSTOM"));

        // The preset supplies a host and port only where the node leaves them blank, so a provider choice
        // never overwrites something the operator typed.
        String configuredHost = resolve.apply(configuration.getString("smtpHost", ""));
        builder.host = configuredHost.isBlank() ? nullToEmpty(builder.provider.host()) : configuredHost;

        int configuredPort = configuration.getInt("smtpPort", 0);
        builder.port = configuredPort > 0 ? configuredPort : builder.provider.port();

        String configuredSecurity = configuration.getString("security", "");
        builder.security = configuredSecurity.isBlank()
                ? builder.provider.security()
                : SmtpSecurity.parse(configuredSecurity);

        builder.authenticationRequired = configuration.getBoolean("authenticationRequired", true);
        builder.username = resolve.apply(configuration.getString("username", ""));

        if (builder.authenticationRequired) {
            builder.password = resolvePassword(configuration, resolve, secrets, builder);
        }

        builder.fromEmail = resolve.apply(configuration.getString("fromEmail", ""));
        builder.fromName = resolve.apply(configuration.getString("fromName", ""));
        builder.replyTo = resolve.apply(configuration.getString("replyTo", ""));

        builder.to.addAll(addresses(configuration.find("to").orElse(null), resolve));
        builder.cc.addAll(addresses(configuration.find("cc").orElse(null), resolve));
        builder.bcc.addAll(addresses(configuration.find("bcc").orElse(null), resolve));

        builder.subject = resolve.apply(configuration.getString("subject", ""));
        builder.bodyType = EmailBodyType.parse(configuration.getString("bodyType", "TEXT"));
        builder.body = resolve.apply(configuration.getString("body", ""));

        Object rawAttachments = configuration.find("attachments").orElse(null);
        if (rawAttachments instanceof Collection<?> collection) {
            for (Object entry : collection) {
                if (entry instanceof Map<?, ?> map) {
                    builder.attachments.add(EmailAttachment.from((Map<String, Object>) map, resolve));
                }
            }
        }

        builder.connectionTimeoutMillis = configuration.getInt("connectionTimeoutMillis", 15_000);
        builder.readTimeoutMillis = configuration.getInt("readTimeoutMillis", 30_000);

        return new EmailNodeConfiguration(builder);
    }

    /**
     * Finds the password without it ever having been in the workflow.
     *
     * <p>Three ways, in order of preference:
     *
     * <ol>
     *   <li>{@code credentialId} — a named credential, whose parts are stored as the secrets
     *       {@code <id>.username} and {@code <id>.password}. One name in the workflow, both values in the
     *       secret store.</li>
     *   <li>{@code passwordSecret} — the name of a secret holding the password.</li>
     *   <li>A {@code password} field containing {@code ${secret.NAME}}, which the engine's resolver expands.
     *       Accepted because it is the shape an existing workflow may already use.</li>
     * </ol>
     *
     * <p>A literal password in the {@code password} field is refused. Sending it would work, which is exactly
     * why it has to be refused: it would work, and the password would be in the workflow definition, its
     * exports, and its version history forever.
     */
    private static String resolvePassword(NodeConfiguration configuration, UnaryOperator<String> resolve,
                                          SecretProvider secrets, Builder builder) {
        String credentialId = configuration.getString("credentialId", "").trim();
        if (!credentialId.isBlank()) {
            if (builder.username.isBlank()) {
                builder.username = secrets.find(credentialId + ".username").orElse("");
            }
            return secrets.find(credentialId + ".password")
                    .orElseThrow(() -> new PluginConfigurationException(
                            "Credential '" + credentialId + "' has no stored password. Store it as the "
                                    + "secret '" + credentialId + ".password'."));
        }

        String passwordSecret = configuration.getString("passwordSecret", "").trim();
        if (!passwordSecret.isBlank()) {
            return secrets.find(passwordSecret)
                    .orElseThrow(() -> new PluginConfigurationException(
                            "No secret named '" + passwordSecret + "' is available to this plugin. Check the "
                                    + "name, and that the plugin's secret scopes allow it."));
        }

        String inline = configuration.getString("password", "").trim();
        if (inline.isBlank()) {
            return "";
        }
        if (inline.startsWith("${")) {
            // A reference the engine expands, such as ${secret.SMTP_PASSWORD}. The workflow holds the
            // reference, never the value.
            return resolve.apply(inline);
        }
        throw new PluginConfigurationException(
                "A password must not be written into the workflow. Store it as a secret and name it in "
                        + "'passwordSecret', or use a credential id. A literal value here would be readable "
                        + "by anyone who can read this workflow, and would travel in every export of it.");
    }

    /** Accepts a list, or one string holding several addresses separated by commas or semicolons. */
    private static List<String> addresses(Object raw, UnaryOperator<String> resolve) {
        List<String> found = new ArrayList<>();
        if (raw == null) {
            return found;
        }
        if (raw instanceof Collection<?> collection) {
            for (Object entry : collection) {
                addSplit(found, resolve.apply(String.valueOf(entry)));
            }
            return found;
        }
        addSplit(found, resolve.apply(String.valueOf(raw)));
        return found;
    }

    /**
     * Splits on commas and semicolons after resolution.
     *
     * <p>After, not before, because a single variable frequently expands to a list —
     * {@code ${approvers.emails}} becoming three addresses is the ordinary case, and splitting first would
     * send one message to a string containing commas.
     */
    private static void addSplit(List<String> target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String part : value.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                target.add(trimmed);
            }
        }
    }

    /**
     * Everything wrong with these settings.
     *
     * <p>All of it at once. Checking one thing, failing, and making somebody run the workflow again to find
     * the next problem is a poor way to configure a connection with eight required parts.
     *
     * @return the problems, empty when the configuration can be used
     */
    List<String> validate() {
        List<String> problems = new ArrayList<>();

        if (host == null || host.isBlank()) {
            problems.add("An SMTP host is required"
                    + (provider != EmailProvider.CUSTOM && !provider.suppliesHost()
                            ? ": " + provider.label() + " uses a regional endpoint, so it must be supplied."
                            : "."));
        }
        if (port < MIN_PORT || port > MAX_PORT) {
            problems.add("The SMTP port must be between " + MIN_PORT + " and " + MAX_PORT + ", not " + port
                    + ".");
        }
        if (authenticationRequired) {
            if (username == null || username.isBlank()) {
                problems.add("Authentication is required, so a username is needed.");
            }
            if (password == null || password.isBlank()) {
                problems.add("Authentication is required, but no password could be resolved. Name a secret "
                        + "in 'passwordSecret', or a credential in 'credentialId'.");
            }
        }
        if (fromEmail == null || fromEmail.isBlank()) {
            problems.add("A from address is required.");
        } else if (!ADDRESS.matcher(fromEmail).matches()) {
            problems.add("'" + fromEmail + "' is not a valid from address.");
        }
        if (to.isEmpty()) {
            problems.add("At least one recipient is required.");
        }
        for (String recipient : to) {
            if (!ADDRESS.matcher(recipient).matches()) {
                problems.add("'" + recipient + "' is not a valid recipient address.");
            }
        }
        if (subject == null || subject.isBlank()) {
            problems.add("A subject is required.");
        }
        if (body == null || body.isBlank()) {
            problems.add("A body is required.");
        }
        for (EmailAttachment attachment : attachments) {
            if (!attachment.source().isSupported()) {
                problems.add("Attachment '" + attachment.fileName() + "' uses source "
                        + attachment.source() + ", which this plugin cannot fetch.");
            }
        }
        return problems;
    }

    /** @return every recipient, for the count reported on the result */
    int recipientCount() {
        return to.size() + cc.size() + bcc.size();
    }

    EmailProvider provider() {
        return provider;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    SmtpSecurity security() {
        return security;
    }

    boolean authenticationRequired() {
        return authenticationRequired;
    }

    String username() {
        return username;
    }

    /** The resolved password. Held for one send and never written anywhere. */
    String password() {
        return password;
    }

    String fromEmail() {
        return fromEmail;
    }

    Optional<String> fromName() {
        return fromName == null || fromName.isBlank() ? Optional.empty() : Optional.of(fromName);
    }

    List<String> to() {
        return to;
    }

    List<String> cc() {
        return cc;
    }

    List<String> bcc() {
        return bcc;
    }

    Optional<String> replyTo() {
        return replyTo == null || replyTo.isBlank() ? Optional.empty() : Optional.of(replyTo);
    }

    String subject() {
        return subject;
    }

    EmailBodyType bodyType() {
        return bodyType;
    }

    String body() {
        return body;
    }

    List<EmailAttachment> attachments() {
        return attachments;
    }

    int connectionTimeoutMillis() {
        return connectionTimeoutMillis;
    }

    int readTimeoutMillis() {
        return readTimeoutMillis;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * A description safe to log: the connection, never the credentials.
     *
     * <p>No username either. It is an email address on most providers, and putting one in every log line is a
     * way of publishing an address list.
     */
    @Override
    public String toString() {
        return "smtp " + host + ":" + port + " " + security + (authenticationRequired ? " authenticated" : "");
    }

    /** Mutable while reading a configuration, immutable afterwards. */
    private static final class Builder {
        private EmailProvider provider = EmailProvider.CUSTOM;
        private String host = "";
        private int port;
        private SmtpSecurity security = SmtpSecurity.STARTTLS;
        private boolean authenticationRequired = true;
        private String username = "";
        private String password = "";
        private String fromEmail = "";
        private String fromName = "";
        private final List<String> to = new ArrayList<>();
        private final List<String> cc = new ArrayList<>();
        private final List<String> bcc = new ArrayList<>();
        private String replyTo = "";
        private String subject = "";
        private EmailBodyType bodyType = EmailBodyType.TEXT;
        private String body = "";
        private final List<EmailAttachment> attachments = new ArrayList<>();
        private int connectionTimeoutMillis = 15_000;
        private int readTimeoutMillis = 30_000;
    }
}
