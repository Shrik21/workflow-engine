package com.orchpilot.workflow.plugins.restapi;

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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Calls any HTTP endpoint and maps the response into workflow variables.
 *
 * <p>The generic integration node, and a demonstration of two things the SDK is designed for:
 *
 * <ul>
 *   <li><b>Structured response mapping.</b> Outputs are published both flat ({@code statusCode}, {@code body}) and
 *       nested under {@code response}, so a workflow can read {@code ${node.call-1.response.status}} exactly as the
 *       specification describes, and a JSON body is parsed so {@code ${node.call-1.json.id}} works without the author
 *       writing any parsing logic.</li>
 *   <li><b>Plugin-managed idempotency.</b> Unlike the SendGrid node, this one is declared idempotent, because whether
 *       a REST call is safe to repeat depends on the method and the endpoint, which the engine cannot know. An author
 *       who is calling something unsafe sets {@code deduplicate: true} and the plugin claims the engine's idempotency
 *       key through {@link com.orchpilot.workflow.sdk.context.IdempotencyStore} itself. Defaulting to on would break a
 *       polling loop, where every iteration is deliberately a repeat of the same request.</li>
 * </ul>
 *
 * <p>Thread-safe: the only field is the context, written once during {@code initialize}.
 */
public class RestApiPlugin implements WorkflowNodePlugin {

    /** Node type contributed by this plugin. */
    public static final String NODE_TYPE = "REST_API_CALL";

    private static final String PLUGIN_ID = "restapi";
    private static final String PLUGIN_VERSION = "1.0.2";

    private static final Set<String> SUPPORTED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE",
            "HEAD", "OPTIONS");

    private volatile PluginContext context;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "REST API Plugin";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "Calls an HTTP endpoint and maps the response into workflow variables";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) throws PluginException {
        this.context = pluginContext;
        pluginContext.logger().info("REST API plugin initialised");
    }

    @Override
    public void destroy() {
        if (context != null) {
            context.logger().info("REST API plugin destroyed");
        }
    }

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        return List.of(NodeDefinition.builder(NODE_TYPE)
                .displayName("REST API Call")
                .category("Integration")
                .icon("cloud")
                .description("Calls an HTTP endpoint. Supports GET, POST, PUT, PATCH and DELETE, with headers, query "
                        + "parameters, a JSON or raw body, and a bearer token taken from a secret.")
                .configurationSchema(SchemaBuilder.object()
                        .select("method", "Method", List.of("GET", "POST", "PUT", "PATCH", "DELETE"), true)
                        .withDefault("method", "GET")
                        .string("url", "URL", true)
                        .map("headers", "Headers", false)
                        .map("queryParams", "Query parameters", false)
                        .text("body", "Body (object or raw string)", false)
                        .secretRef("bearerTokenSecret", "Bearer token secret name", false)
                        .withDescription("bearerTokenSecret",
                                "Adds Authorization: Bearer <secret>. Use this instead of putting a token in headers.")
                        .integer("timeoutMillis", "Timeout (ms)", false)
                        .withDefault("timeoutMillis", 30000)
                        .bool("failOnErrorStatus", "Fail the node on a non-2xx response", false)
                        .withDefault("failOnErrorStatus", true)
                        .bool("deduplicate", "Deduplicate retries of this call", false)
                        .withDescription("deduplicate",
                                "Turn on for calls that are not safe to repeat. Leave off for reads and for polling "
                                        + "loops, where repeating the same request is the point.")
                        .build())
                .outputVariables("statusCode", "body", "headers", "response", "json", "durationMillis")
                // Declared idempotent so the engine does not install its guard. Deduplication is opt-in through the
                // deduplicate flag, because safety depends on the method and endpoint, not on the node type.
                .idempotent(true)
                .supportsRetry(true)
                .build());
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext executionContext) {
        NodeConfiguration configuration = executionContext.configuration();
        String method = configuration.getString("method", "GET").trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_METHODS.contains(method)) {
            throw new PluginConfigurationException("Unsupported HTTP method '" + method + "'. Supported: "
                    + SUPPORTED_METHODS);
        }
        String url = withQueryParameters(configuration.requireString("url"), configuration);
        boolean deduplicate = configuration.getBoolean("deduplicate", false);
        String key = executionContext.idempotencyKey();

        if (deduplicate) {
            Optional<Map<String, Object>> previous = context.idempotency().lookup(key);
            if (previous.isPresent()) {
                context.logger().info("Replaying a previous response for {} {} instead of calling again", method,
                        url);
                return NodeExecutionResult.success(previous.get());
            }
            if (!context.idempotency().claim(key, Duration.ofMinutes(10))) {
                return NodeExecutionResult.failure("REST_CALL_IN_FLIGHT",
                        "Another attempt at this call is already in progress", true);
            }
        }

        try {
            NodeExecutionResult result = call(executionContext, configuration, method, url);
            if (deduplicate) {
                if (result.isSuccess()) {
                    context.idempotency().complete(key, result.outputs());
                } else {
                    // Release rather than complete: a failed call must be retryable, and holding the key would make
                    // every subsequent attempt report itself as in flight until the claim expired.
                    context.idempotency().release(key, result.errorCode(), result.errorMessage());
                }
            }
            return result;
        } catch (RuntimeException ex) {
            if (deduplicate) {
                context.idempotency().release(key, "REST_CALL_FAILED", ex.getMessage());
            }
            throw ex;
        }
    }

    private NodeExecutionResult call(NodeExecutionContext executionContext, NodeConfiguration configuration,
                                     String method, String url) {
        String body = resolveBody(configuration);
        HttpRequestSpec.Builder builder = HttpRequestSpec.builder(method, url)
                .headers(configuration.getStringMap("headers"))
                .timeoutMillis(configuration.getLong("timeoutMillis", 30_000));
        if (body != null) {
            builder.body(body);
            if (!configuration.getStringMap("headers").containsKey("Content-Type")) {
                builder.header("Content-Type", "application/json");
            }
        }
        String tokenSecret = configuration.getString("bearerTokenSecret", null);
        if (tokenSecret != null && !tokenSecret.isBlank()) {
            builder.header("Authorization", "Bearer " + context.secrets().require(tokenSecret));
        }

        context.logger().info("Calling {} {}", method, url);
        HttpResponseView response = context.http().execute(builder.build());

        Map<String, Object> outputs = buildOutputs(response);
        boolean failOnError = configuration.getBoolean("failOnErrorStatus", true);
        if (!response.isSuccess() && failOnError) {
            boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
            context.logger().warn("{} {} returned {}", method, url, response.statusCode());
            return NodeExecutionResult.builder(com.orchpilot.workflow.sdk.node.NodeExecutionStatus.FAILED)
                    .outputs(outputs)
                    .error("HTTP_" + response.statusCode(),
                            method + " " + url + " returned " + response.statusCode() + ": " + summarize(response))
                    .retryable(retryable)
                    .build();
        }
        context.logger().debug("{} {} returned {} in {} ms", method, url, response.statusCode(),
                response.durationMillis());
        return NodeExecutionResult.success(outputs);
    }

    /**
     * Publishes the response in three shapes so that a workflow author does not have to reshape it.
     *
     * <p>Flat keys for convenience, a nested {@code response} map so {@code response.status} reads naturally, and a
     * parsed {@code json} object when the body is JSON so nested fields are addressable without any parsing step in
     * the workflow.
     */
    private Map<String, Object> buildOutputs(HttpResponseView response) {
        Map<String, Object> headers = new LinkedHashMap<>();
        response.headers().forEach((name, values) ->
                headers.put(name, values == null || values.isEmpty() ? null : values.get(0)));

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("status", response.statusCode());
        nested.put("body", response.body());
        nested.put("headers", headers);

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("statusCode", response.statusCode());
        outputs.put("body", response.body());
        outputs.put("headers", headers);
        outputs.put("response", nested);
        outputs.put("durationMillis", response.durationMillis());
        parseJson(response.body()).ifPresent(parsed -> outputs.put("json", parsed));
        return outputs;
    }

    private Optional<Object> parseJson(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        char first = body.trim().charAt(0);
        if (first != '{' && first != '[') {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Json.parse(body));
        } catch (RuntimeException ex) {
            // A body that claims to be JSON but is not should not fail the node: the raw body is still published.
            context.logger().debug("Response body looked like JSON but did not parse: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Accepts either a structure, which is serialised to JSON, or a raw string, which is sent as given. Both are
     * common: a REST API wants JSON, a legacy endpoint may want form-encoded or XML.
     */
    private String resolveBody(NodeConfiguration configuration) {
        Object raw = configuration.find("body").orElse(null);
        if (raw == null) {
            return null;
        }
        if (raw instanceof CharSequence) {
            String text = raw.toString();
            return text.isBlank() ? null : text;
        }
        return Json.write(raw);
    }

    private String withQueryParameters(String url, NodeConfiguration configuration) {
        Map<String, String> parameters = configuration.getStringMap("queryParams");
        if (parameters.isEmpty()) {
            return url;
        }
        StringBuilder builder = new StringBuilder(url);
        builder.append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (!first) {
                builder.append('&');
            }
            first = false;
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static String summarize(HttpResponseView response) {
        String body = response.body();
        if (body == null || body.isBlank()) {
            return "no response body";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}
