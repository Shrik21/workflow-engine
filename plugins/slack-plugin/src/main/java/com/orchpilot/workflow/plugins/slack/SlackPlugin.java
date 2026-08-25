package com.orchpilot.workflow.plugins.slack;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.exception.PluginException;
import com.orchpilot.workflow.sdk.json.Json;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;
import com.orchpilot.workflow.sdk.schema.SchemaBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Posts messages to Slack.
 *
 * <p>This module exists to prove the platform's central claim. Nothing in the engine references Slack, this plugin was
 * not part of the engine's build, and uploading its JAR to a running instance makes {@code SLACK_MESSAGE} appear in
 * {@code GET /api/nodes} and become usable in a new workflow without a rebuild or a restart.
 *
 * <p>It also shows a failure mode worth handling explicitly: Slack answers {@code chat.postMessage} with HTTP 200 and
 * {@code "ok": false} for application-level errors such as {@code channel_not_found}. A plugin that only checked the
 * status code would report success for a message nobody received.
 *
 * <p>Thread-safe: the only field is the context, written once during {@code initialize}.
 */
public class SlackPlugin implements WorkflowNodePlugin {

    /** Node type contributed by this plugin. */
    public static final String NODE_TYPE = "SLACK_MESSAGE";

    private static final String PLUGIN_ID = "slack";
    private static final String PLUGIN_VERSION = "1.0.1";
    private static final String DEFAULT_ENDPOINT = "https://slack.com/api/chat.postMessage";

    /** Slack error codes that are worth retrying; the rest are configuration or permission problems. */
    private static final List<String> RETRYABLE_SLACK_ERRORS = List.of("ratelimited", "rate_limited",
            "service_unavailable", "internal_error", "fatal_error", "request_timeout");

    private volatile PluginContext context;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "Slack Plugin";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "Posts messages to a Slack channel using a bot token";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) throws PluginException {
        this.context = pluginContext;
        pluginContext.logger().info("Slack plugin initialised");
    }

    @Override
    public void destroy() {
        if (context != null) {
            context.logger().info("Slack plugin destroyed");
        }
    }

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        return List.of(NodeDefinition.builder(NODE_TYPE)
                .displayName("Send Slack Message")
                .category("Communication")
                .icon("message")
                .description("Posts a message to a Slack channel. The bot token is referenced by secret name.")
                .configurationSchema(SchemaBuilder.object()
                        .secretRef("botTokenSecret", "Slack bot token secret name", true)
                        .string("channel", "Channel id or name", true)
                        .text("text", "Message text", true)
                        .string("threadTs", "Thread timestamp to reply in", false)
                        .text("blocksJson", "Block Kit blocks as a JSON array", false)
                        .withDescription("blocksJson",
                                "When set, text is used as the notification fallback only")
                        .bool("unfurlLinks", "Unfurl links", false)
                        .withDefault("unfurlLinks", true)
                        .build())
                .outputVariables("ok", "channel", "ts", "permalink")
                // Posting a message is not repeatable, so the engine guards retries and resumes.
                .idempotent(false)
                .supportsRetry(true)
                .supportsAI(true)
                .destructive(true)
                .build());
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext executionContext) {
        NodeConfiguration configuration = executionContext.configuration();
        String tokenSecret = configuration.requireString("botTokenSecret");
        String channel = configuration.requireString("channel");
        String text = configuration.requireString("text");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", channel);
        payload.put("text", text);
        payload.put("unfurl_links", configuration.getBoolean("unfurlLinks", true));
        String threadTs = configuration.getString("threadTs", null);
        if (threadTs != null && !threadTs.isBlank()) {
            payload.put("thread_ts", threadTs);
        }
        String blocksJson = configuration.getString("blocksJson", null);
        if (blocksJson != null && !blocksJson.isBlank()) {
            try {
                payload.put("blocks", Json.parseArray(blocksJson));
            } catch (RuntimeException ex) {
                return NodeExecutionResult.failure("SLACK_BLOCKS_INVALID",
                        "blocksJson is not a valid JSON array: " + ex.getMessage());
            }
        }

        String token = context.secrets().require(tokenSecret);
        HttpRequestSpec request = HttpRequestSpec.post(endpoint(), Json.write(payload))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .timeoutMillis(configuration.getLong("timeoutMillis", 15_000))
                .build();

        context.logger().info("Posting a message to Slack channel {}", channel);
        HttpResponseView response = context.http().execute(request);

        if (!response.isSuccess()) {
            boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
            return NodeExecutionResult.failure("SLACK_HTTP_" + response.statusCode(),
                    "Slack returned HTTP " + response.statusCode(), retryable);
        }
        return interpret(response);
    }

    /**
     * Reads Slack's application-level envelope.
     *
     * <p>Slack returns HTTP 200 with {@code "ok": false} for errors like {@code channel_not_found} and
     * {@code invalid_auth}. Treating a 200 as success would silently swallow those.
     */
    @SuppressWarnings("unchecked")
    private NodeExecutionResult interpret(HttpResponseView response) {
        Map<String, Object> parsed;
        try {
            parsed = Json.parseObject(response.body());
        } catch (RuntimeException ex) {
            return NodeExecutionResult.failure("SLACK_RESPONSE_UNPARSEABLE",
                    "Slack returned 200 with a body that is not JSON: " + ex.getMessage(), true);
        }

        boolean ok = Boolean.TRUE.equals(parsed.get("ok"));
        if (!ok) {
            String error = String.valueOf(parsed.getOrDefault("error", "unknown_error"));
            boolean retryable = RETRYABLE_SLACK_ERRORS.contains(error);
            context.logger().warn("Slack rejected the message: {}", error);
            return NodeExecutionResult.failure("SLACK_" + error.toUpperCase(java.util.Locale.ROOT),
                    "Slack rejected the message: " + error, retryable);
        }

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("ok", Boolean.TRUE);
        outputs.put("channel", parsed.get("channel"));
        outputs.put("ts", parsed.get("ts"));
        if (parsed.get("message") instanceof Map) {
            Object permalink = ((Map<String, Object>) parsed.get("message")).get("permalink");
            if (permalink != null) {
                outputs.put("permalink", permalink);
            }
        }
        return NodeExecutionResult.success(outputs);
    }

    private String endpoint() {
        if (context == null) {
            return DEFAULT_ENDPOINT;
        }
        return context.settings().getString("endpoint", DEFAULT_ENDPOINT);
    }
}
