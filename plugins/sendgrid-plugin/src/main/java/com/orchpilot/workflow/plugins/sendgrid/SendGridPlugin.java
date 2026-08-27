package com.orchpilot.workflow.plugins.sendgrid;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.exception.PluginException;
import com.orchpilot.workflow.sdk.json.Json;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;
import com.orchpilot.workflow.sdk.schema.SchemaBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends transactional email through the SendGrid v3 API.
 *
 * <p>A sample plugin, and a demonstration of the properties a production integration needs:
 *
 * <ul>
 *   <li><b>No credential in the workflow.</b> The node configures {@code apiKeySecret}, the <em>name</em> of a
 *       secret. The value is fetched through the scoped secret provider at execution time, is registered for log
 *       redaction automatically, and never appears in the workflow definition, the execution record or the plugin
 *       execution log.</li>
 *   <li><b>Idempotency.</b> The node is declared non-idempotent, so the engine's guard replays a previous successful
 *       send instead of repeating it on a retry or a post-crash resume. Belt and braces, the engine's idempotency
 *       key is also passed to SendGrid as an {@code Idempotency-Key} header, so even a send the engine did not
 *       record as successful is deduplicated by the provider.</li>
 *   <li><b>Failure classification.</b> 429 and 5xx are marked retryable so the engine's backoff applies; 4xx are
 *       not, because a malformed address will fail identically forever.</li>
 *   <li><b>No dependencies.</b> The request body is built with the SDK's JSON writer and sent through the
 *       engine-provided HTTP client, so this JAR contains one class and nothing else.</li>
 * </ul>
 *
 * <p>Thread-safe: the only field is the context, written once during {@code initialize}.
 */
public class SendGridPlugin implements WorkflowNodePlugin {

    /** Node type contributed by this plugin. */
    public static final String NODE_TYPE = "SENDGRID_EMAIL";

    private static final String PLUGIN_ID = "sendgrid";
    private static final String PLUGIN_VERSION = "1.0.2";
    private static final String DEFAULT_ENDPOINT = "https://api.sendgrid.com/v3/mail/send";

    private volatile PluginContext context;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "SendGrid Plugin";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "Sends transactional email through the SendGrid v3 API";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) throws PluginException {
        this.context = pluginContext;
        pluginContext.logger().info("SendGrid plugin initialised (endpoint {})", endpoint());
    }

    @Override
    public void destroy() {
        // Nothing to release: no threads, no pools, no open handles. A plugin that allocated any of those would
        // have to close them here, or its class loader could never be collected.
        if (context != null) {
            context.logger().info("SendGrid plugin destroyed");
        }
    }

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        return List.of(NodeDefinition.builder(NODE_TYPE)
                .displayName("Send Email (SendGrid)")
                .category("Communication")
                .icon("email")
                .description("Sends an email through SendGrid. Credentials are referenced by secret name, never "
                        + "embedded in the workflow.")
                .configurationSchema(SchemaBuilder.object()
                        .secretRef("apiKeySecret", "SendGrid API key secret name", true)
                        .withDescription("apiKeySecret",
                                "Name of a stored secret, for example sendgrid.apiKey. Not the key itself.")
                        .string("from", "From address", true)
                        .string("fromName", "From name", false)
                        .string("to", "To address(es), comma separated", true)
                        .string("cc", "Cc address(es), comma separated", false)
                        .string("bcc", "Bcc address(es), comma separated", false)
                        .string("replyTo", "Reply-to address", false)
                        .string("subject", "Subject", true)
                        .text("body", "Body", true)
                        .select("contentType", "Body content type", List.of("text/plain", "text/html"), false)
                        .withDefault("contentType", "text/plain")
                        .bool("sandbox", "Sandbox mode (validate without sending)", false)
                        .withDescription("sandbox", "Useful for testing a workflow end to end without delivering")
                        .build())
                .outputVariables("statusCode", "messageId", "accepted", "recipientCount")
                // Sending email is not repeatable. Declaring this makes the engine replay a recorded success on a
                // retry or a resume instead of sending a second copy.
                .idempotent(false)
                .supportsRetry(true)
                .build());
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext executionContext) {
        NodeConfiguration configuration = executionContext.configuration();

        String secretName = configuration.requireString("apiKeySecret");
        String from = configuration.requireString("from");
        String subject = configuration.requireString("subject");
        String body = configuration.requireString("body");
        List<String> to = addresses(configuration, "to");
        if (to.isEmpty()) {
            throw new PluginConfigurationException("At least one 'to' address is required");
        }

        String apiKey = context.secrets().require(secretName);
        String payload = Json.write(buildPayload(configuration, from, subject, body, to));

        HttpRequestSpec request = HttpRequestSpec.post(endpoint(), payload)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                // Passing the engine's key through to the provider means the provider deduplicates too, covering
                // the window where a send succeeded but the engine died before recording it.
                .header("Idempotency-Key", executionContext.idempotencyKey())
                .timeoutMillis(configuration.getLong("timeoutMillis", 30_000))
                .build();

        context.logger().info("Sending email to {} recipient(s) with subject '{}'", to.size(), subject);
        HttpResponseView response = context.http().execute(request);

        if (response.isSuccess()) {
            Map<String, Object> outputs = new LinkedHashMap<>();
            outputs.put("statusCode", response.statusCode());
            outputs.put("messageId", response.firstHeader("X-Message-Id"));
            outputs.put("accepted", Boolean.TRUE);
            outputs.put("recipientCount", to.size());
            return NodeExecutionResult.success(outputs);
        }
        return failure(response);
    }

    /**
     * Classifies a SendGrid failure.
     *
     * <p>The distinction matters: retrying a 429 is the correct response to rate limiting, while retrying a 400
     * caused by an invalid address just delays the failure and burns the retry budget.
     */
    private NodeExecutionResult failure(HttpResponseView response) {
        boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
        String detail = extractErrors(response.body());
        String code = switch (response.statusCode()) {
            case 401, 403 -> "SENDGRID_UNAUTHORIZED";
            case 413 -> "SENDGRID_PAYLOAD_TOO_LARGE";
            case 429 -> "SENDGRID_RATE_LIMITED";
            default -> response.statusCode() >= 500 ? "SENDGRID_UNAVAILABLE" : "SENDGRID_REQUEST_REJECTED";
        };
        context.logger().warn("SendGrid returned {}: {}", response.statusCode(), detail);
        return NodeExecutionResult.failure(code,
                "SendGrid returned " + response.statusCode() + ": " + detail, retryable);
    }

    /**
     * Pulls the human-readable messages out of SendGrid's error envelope so the workflow's error message says what
     * went wrong rather than just quoting a status code.
     */
    @SuppressWarnings("unchecked")
    private String extractErrors(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "no response body";
        }
        try {
            Object parsed = Json.parse(responseBody);
            if (parsed instanceof Map && ((Map<String, Object>) parsed).get("errors") instanceof List) {
                List<Object> errors = (List<Object>) ((Map<String, Object>) parsed).get("errors");
                List<String> messages = new ArrayList<>();
                for (Object error : errors) {
                    if (error instanceof Map) {
                        Object message = ((Map<String, Object>) error).get("message");
                        Object field = ((Map<String, Object>) error).get("field");
                        messages.add(field == null ? String.valueOf(message) : field + ": " + message);
                    }
                }
                if (!messages.isEmpty()) {
                    return String.join("; ", messages);
                }
            }
        } catch (RuntimeException ex) {
            // Not JSON, or an unexpected shape. Fall through to the raw body.
        }
        return responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody;
    }

    private Map<String, Object> buildPayload(NodeConfiguration configuration, String from, String subject,
                                             String body, List<String> to) {
        Map<String, Object> personalization = new LinkedHashMap<>();
        personalization.put("to", to.stream().map(SendGridPlugin::emailObject).toList());
        List<String> cc = addresses(configuration, "cc");
        if (!cc.isEmpty()) {
            personalization.put("cc", cc.stream().map(SendGridPlugin::emailObject).toList());
        }
        List<String> bcc = addresses(configuration, "bcc");
        if (!bcc.isEmpty()) {
            personalization.put("bcc", bcc.stream().map(SendGridPlugin::emailObject).toList());
        }

        Map<String, Object> sender = new LinkedHashMap<>();
        sender.put("email", from);
        String fromName = configuration.getString("fromName", null);
        if (fromName != null && !fromName.isBlank()) {
            sender.put("name", fromName);
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", configuration.getString("contentType", "text/plain"));
        content.put("value", body);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("personalizations", List.of(personalization));
        payload.put("from", sender);
        payload.put("subject", subject);
        payload.put("content", List.of(content));

        String replyTo = configuration.getString("replyTo", null);
        if (replyTo != null && !replyTo.isBlank()) {
            payload.put("reply_to", emailObject(replyTo));
        }
        if (configuration.getBoolean("sandbox", false)) {
            payload.put("mail_settings", Map.of("sandbox_mode", Map.of("enable", Boolean.TRUE)));
        }
        return payload;
    }

    private static Map<String, Object> emailObject(String address) {
        Map<String, Object> email = new LinkedHashMap<>();
        email.put("email", address);
        return email;
    }

    /**
     * Splits a comma or semicolon separated address list, tolerating the spacing people actually type.
     */
    private static List<String> addresses(NodeConfiguration configuration, String key) {
        String raw = configuration.getString(key, null);
        List<String> addresses = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return addresses;
        }
        for (String part : raw.split("[,;]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                addresses.add(trimmed);
            }
        }
        return addresses;
    }

    private String endpoint() {
        if (context == null) {
            return DEFAULT_ENDPOINT;
        }
        // Overridable so a deployment can point the plugin at a mock or a regional endpoint without a code change.
        return context.settings().getString("endpoint", DEFAULT_ENDPOINT);
    }
}
