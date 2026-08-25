package com.orchpilot.workflow.plugins.email;

import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sends email through any SMTP server, with the connection configured on the node.
 *
 * <h2>Why the connection lives on the node</h2>
 *
 * The other email plugin on this platform talks to one provider's API. This one talks to whatever SMTP server
 * an operator has credentials for: their own relay, their company's Microsoft 365 tenant, a regional SES
 * endpoint. That means host, port, security and authentication are configuration rather than constants, and
 * the node is only as useful as its ability to express an unusual combination of them.
 *
 * <h2>What is deliberately not configurable</h2>
 *
 * Whether the sender is allowed to send as the from address. That is decided by the server through SPF, DKIM,
 * DMARC and its own sender verification, and a client cannot grant itself permission. A node that appeared to
 * offer that would be offering to forge mail.
 *
 * <h2>Two operations, one node</h2>
 *
 * {@code operation: SEND} sends. {@code operation: TEST_CONNECTION} performs the whole connection — DNS, TCP,
 * TLS, AUTH — and disconnects without sending anything, so a configuration can be proven before a workflow
 * depends on it. The second exists because the alternative way to test SMTP settings is to send real mail to a
 * real person.
 *
 * <h2>Non-idempotent, on purpose</h2>
 *
 * Sending is not repeatable. The node declares itself non-idempotent so the engine's guard replays a recorded
 * success rather than sending a second copy after a retry or a restart.
 *
 * <p>Thread-safe: the only field is the context, written once during {@code initialize}.
 */
public class EmailPlugin implements WorkflowNodePlugin {

    /** Node type contributed by this plugin. */
    public static final String NODE_TYPE = "EMAIL_SEND";

    private static final String PLUGIN_ID = "email";

    /** Must match the POM and the JAR manifest: the engine probes all three and refuses a disagreement. */
    private static final String PLUGIN_VERSION = "1.0.3";

    /** Send, or prove the connection works without sending. */
    private static final String OPERATION_SEND = "SEND";
    private static final String OPERATION_TEST = "TEST_CONNECTION";

    private volatile PluginContext context;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "Email";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "Sends email through any SMTP server, with the connection configured on the node";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) {
        this.context = pluginContext;
        pluginContext.logger().info("Email plugin initialised; SMTP settings come from each node");
    }

    /**
     * Nothing to release.
     *
     * <p>No connection outlives a send: each one opens a session, uses it and closes the transport. There is
     * deliberately no pool to drain here, which is why this method is empty rather than absent.
     */
    @Override
    public void destroy() {
        // Intentionally empty; see the note above.
    }

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        return List.of(NodeDefinition.builder(NODE_TYPE)
                .displayName("Send Email")
                .description("Sends an email through an SMTP server. The connection is configured here, so "
                        + "any provider the operator has credentials for can be used.")
                .category("Communication")
                .icon("email")
                .configurationSchema(schema())
                .outputVariables("email.success", "email.messageId", "email.sentAt", "email.recipientCount")
                .idempotent(false)
                .supportsRetry(true)
                .supportsAI(true)
                .destructive(true)
                .build());
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext execution) {
        String operation = execution.configuration()
                .getString("operation", OPERATION_SEND)
                .trim()
                .toUpperCase(Locale.ROOT);

        EmailNodeConfiguration settings;
        try {
            settings = EmailNodeConfiguration.from(execution.configuration(), execution::resolve,
                    context.secrets());
        } catch (PluginConfigurationException ex) {
            return NodeExecutionResult.failure(EmailErrors.CONFIGURATION_INVALID, ex.getMessage(), false);
        }

        if (OPERATION_TEST.equals(operation)) {
            return testConnection(settings, execution);
        }

        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            // Every problem at once. Failing on the first would mean one workflow run per mistake.
            return NodeExecutionResult.failure(EmailErrors.CONFIGURATION_INVALID,
                    "The email node is not correctly configured: " + String.join(" ", problems), false);
        }

        try {
            SmtpEmailSender.SentMessage sent = SmtpEmailSender.send(settings);

            // Never the body, never a recipient address, never the credentials: a log line is read by more
            // people than the email was addressed to.
            context.logger().info("Email sent via " + settings.host() + " messageId=" + sent.messageId()
                    + " recipients=" + sent.recipientCount());

            Map<String, Object> email = new LinkedHashMap<>();
            email.put("success", true);
            email.put("messageId", sent.messageId());
            email.put("sentAt", sent.sentAt().toString());
            email.put("recipientCount", sent.recipientCount());
            return NodeExecutionResult.success(outputs(email));

        } catch (IllegalStateException ex) {
            // An attachment this plugin cannot fetch. Configuration, not transport.
            return NodeExecutionResult.failure(EmailErrors.CONFIGURATION_INVALID, ex.getMessage(), false);
        } catch (Exception ex) {
            EmailErrors.Classification classification =
                    EmailErrors.classify(ex, settings.host(), settings.port());
            context.logger().warn("Email send failed: " + classification.code() + " "
                    + classification.message());
            return NodeExecutionResult.failure(classification.code(), classification.message(),
                    classification.retryable());
        }
    }

    /**
     * Proves the connection works without sending anything.
     *
     * <p>Validates only what a connection needs — host, port, credentials — so settings can be tested before
     * the message is written.
     */
    private NodeExecutionResult testConnection(EmailNodeConfiguration settings,
                                               NodeExecutionContext execution) {
        List<String> problems = new ArrayList<>();
        if (settings.host().isBlank()) {
            problems.add("An SMTP host is required.");
        }
        if (settings.port() < 1 || settings.port() > 65_535) {
            problems.add("The SMTP port must be between 1 and 65535.");
        }
        if (settings.authenticationRequired()
                && (settings.username().isBlank() || settings.password().isBlank())) {
            problems.add("Authentication is required, so a username and a resolvable password are needed.");
        }
        if (!problems.isEmpty()) {
            return NodeExecutionResult.failure(EmailErrors.CONFIGURATION_INVALID,
                    String.join(" ", problems), false);
        }

        try {
            SmtpEmailSender.testConnection(settings);
            context.logger().info("SMTP connection verified: " + settings);

            Map<String, Object> email = new LinkedHashMap<>();
            email.put("success", true);
            email.put("connectionVerified", true);
            email.put("host", settings.host());
            email.put("port", settings.port());
            email.put("security", settings.security().name());
            return NodeExecutionResult.success(outputs(email));

        } catch (Exception ex) {
            EmailErrors.Classification classification =
                    EmailErrors.classify(ex, settings.host(), settings.port());
            return NodeExecutionResult.failure(classification.code(), classification.message(),
                    classification.retryable());
        }
    }

    /**
     * The form the designer renders.
     *
     * <p>Built by hand rather than with the SDK's schema builder, because this node needs field types the
     * builder does not offer: arrays of addresses, an array of attachment objects, and a password field that
     * names a secret rather than holding one.
     */
    /**
     * Publishes the result as a nested {@code email} object, never as dotted keys.
     *
     * <h2>Why a dot in an output key breaks an execution</h2>
     *
     * The engine publishes a node's outputs into the execution's variable store and persists that store as a
     * MongoDB document. Spring Data refuses a map key containing a dot, so {@code email.messageId} as a
     * literal key throws while <em>saving the execution</em> — after the message has already been sent. The
     * workflow then sits in RUNNING at whatever node last persisted cleanly, with the mail delivered and
     * nothing recorded to say so.
     *
     * <p>Nothing is lost: {@code VariableMapper} resolves a dotted output name into a structure, so an output
     * mapping of {@code email.messageId} reads this exactly as it read the flat form.
     *
     * @param email the fields to publish
     * @return the outputs, with {@code success} also at the top level for a decision node to branch on
     */
    private static Map<String, Object> outputs(Map<String, Object> email) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("email", email);
        outputs.put("success", Boolean.TRUE.equals(email.get("success")));
        return outputs;
    }

    private static Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();

        properties.put("operation", select("Operation",
                List.of(OPERATION_SEND, OPERATION_TEST), OPERATION_SEND,
                "SEND delivers the message. TEST_CONNECTION performs the whole connection and disconnects "
                        + "without sending anything."));

        properties.put("provider", select("SMTP provider",
                Arrays.stream(EmailProvider.values()).map(Enum::name).toList(),
                EmailProvider.CUSTOM.name(),
                "Fills in the host, port and security below. Every one of them stays editable."));

        properties.put("smtpHost", field("string", "SMTP host",
                "For example smtp.company.com. Supports variables, such as ${smtp.host}."));
        properties.put("smtpPort", field("integer", "SMTP port",
                "Any port from 1 to 65535. Commonly 587 for STARTTLS, 465 for SSL/TLS, 25 for a relay."));

        properties.put("security", select("Security",
                Arrays.stream(SmtpSecurity.values()).map(Enum::name).toList(),
                SmtpSecurity.STARTTLS.name(),
                "STARTTLS upgrades a plaintext connection; SSL/TLS is encrypted from the first byte. Never "
                        + "both."));

        properties.put("authenticationRequired", field("boolean", "Authentication required",
                "Off only for a relay that accepts unauthenticated mail from this host."));
        properties.put("username", field("string", "SMTP username",
                "Usually the mailbox address. SendGrid requires the literal 'apikey'."));

        Map<String, Object> passwordSecret = field("string", "Password secret",
                "The NAME of a secret holding the password, never the password itself. The value is fetched "
                        + "at execution and never enters the workflow definition.");
        passwordSecret.put("format", "secret-ref");
        properties.put("passwordSecret", passwordSecret);

        properties.put("credentialId", field("string", "Credential id",
                "An alternative to the above: names a stored credential whose username and password are held "
                        + "as the secrets <id>.username and <id>.password."));

        properties.put("fromEmail", field("string", "From email",
                "The address the message is sent from. The server decides whether you may use it."));
        properties.put("fromName", field("string", "From name", "Shown beside the address. Optional."));
        properties.put("replyTo", field("string", "Reply-To", "Optional."));

        properties.put("to", addressList("To", "At least one recipient. Variables may expand to several."));
        properties.put("cc", addressList("CC", "Optional."));
        properties.put("bcc", addressList("BCC",
                "Optional. Hidden from the other recipients by the transport."));

        properties.put("subject", field("string", "Subject", "Supports variables."));
        properties.put("bodyType", select("Body type",
                Arrays.stream(EmailBodyType.values()).map(Enum::name).toList(),
                EmailBodyType.TEXT.name(), null));

        Map<String, Object> body = field("string", "Body",
                "Supports variables. Rendered as text or HTML according to the body type.");
        body.put("format", "textarea");
        properties.put("body", body);

        properties.put("attachments", attachmentList());

        properties.put("connectionTimeoutMillis", field("integer", "Connection timeout (ms)",
                "How long to wait for the connection. Defaults to 15000."));
        properties.put("readTimeoutMillis", field("integer", "Read timeout (ms)",
                "How long to wait for the server to answer. Defaults to 30000."));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("smtpHost", "fromEmail", "to", "subject", "body"));
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> field(String type, String title, String description) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", type);
        field.put("title", title);
        if (description != null) {
            field.put("description", description);
        }
        return field;
    }

    private static Map<String, Object> select(String title, List<String> options, String defaultValue,
                                              String description) {
        Map<String, Object> field = field("string", title, description);
        field.put("enum", options);
        field.put("default", defaultValue);
        return field;
    }

    private static Map<String, Object> addressList(String title, String description) {
        Map<String, Object> field = field("array", title, description);
        field.put("items", Map.of("type", "string", "title", "Address"));
        return field;
    }

    private static Map<String, Object> attachmentList() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "object");
        item.put("properties", Map.of(
                "fileName", Map.of("type", "string", "title", "File name"),
                "source", Map.of("type", "string", "title", "Source",
                        "enum", Arrays.stream(EmailAttachment.Source.values()).map(Enum::name).toList()),
                "value", Map.of("type", "string", "title", "Content or variable"),
                "contentType", Map.of("type", "string", "title", "Content type")));

        Map<String, Object> field = field("array", "Attachments",
                "Content comes from a workflow variable or a base64 value. A filesystem path is deliberately "
                        + "not a source: a node that could attach any file the engine can read would be a way "
                        + "to post one off site.");
        field.put("items", item);
        return field;
    }
}
