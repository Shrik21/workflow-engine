package com.orchpilot.workflow.plugins.email.support;

import com.orchpilot.workflow.sdk.context.IdempotencyStore;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.context.PluginDataStore;
import com.orchpilot.workflow.sdk.context.PluginEventPublisher;
import com.orchpilot.workflow.sdk.context.PluginHttpClient;
import com.orchpilot.workflow.sdk.context.PluginLogger;
import com.orchpilot.workflow.sdk.context.PluginSettings;
import com.orchpilot.workflow.sdk.context.SecretProvider;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.VariableView;
import com.orchpilot.workflow.sdk.plugin.PluginDescriptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The engine services the email plugin actually uses, and nothing else.
 *
 * <p>Only a logger, a secret store, resolved configuration and {@code resolve} are reachable from the plugin's
 * code path. Everything else on {@link PluginContext} — the HTTP client, the data store, the idempotency store
 * — throws if touched, which is a test in itself: an email node reaching for the engine's HTTP client would be
 * doing something this plugin has no business doing, and a silent stub would hide it.
 */
public final class TestExecution implements NodeExecutionContext, PluginContext {

    private final NodeConfiguration configuration;
    private final Map<String, String> secrets;
    private final Map<String, String> variables;

    /** Everything logged, so a test can assert that a password never reached a log line. */
    private final List<String> logLines = new ArrayList<>();

    private TestExecution(Map<String, Object> configuration, Map<String, String> secrets,
                          Map<String, String> variables) {
        this.configuration = new MapConfiguration(configuration);
        this.secrets = secrets;
        this.variables = variables;
    }

    public static TestExecution of(Map<String, Object> configuration, Map<String, String> secrets) {
        return new TestExecution(configuration, secrets, Map.of());
    }

    public static TestExecution of(Map<String, Object> configuration, Map<String, String> secrets,
                                   Map<String, String> variables) {
        return new TestExecution(configuration, secrets, variables);
    }

    public List<String> logLines() {
        return logLines;
    }

    // --- what the plugin uses -------------------------------------------------------------------

    @Override
    public NodeConfiguration configuration() {
        return configuration;
    }

    @Override
    public String resolve(String template) {
        if (template == null) {
            return null;
        }
        String result = template;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            result = result.replace("${" + variable.getKey() + "}", variable.getValue());
        }
        return result;
    }

    @Override
    public PluginContext pluginContext() {
        return this;
    }

    @Override
    public SecretProvider secrets() {
        return name -> Optional.ofNullable(secrets.get(name));
    }

    @Override
    public PluginLogger logger() {
        return new PluginLogger() {
            @Override
            public void debug(String message, Object... args) {
                logLines.add(message);
            }

            @Override
            public void info(String message, Object... args) {
                logLines.add(message);
            }

            @Override
            public void warn(String message, Object... args) {
                logLines.add(message);
            }

            @Override
            public void error(String message, Object... args) {
                logLines.add(message);
            }

            @Override
            public void error(String message, Throwable cause) {
                logLines.add(message);
            }

            @Override
            public boolean isDebugEnabled() {
                return true;
            }
        };
    }

    // --- identifiers ----------------------------------------------------------------------------

    @Override
    public String executionId() {
        return "execution-1";
    }

    @Override
    public String workflowId() {
        return "workflow-1";
    }

    @Override
    public int workflowVersion() {
        return 1;
    }

    @Override
    public String nodeId() {
        return "node-1";
    }

    @Override
    public String nodeType() {
        return "EMAIL_SEND";
    }

    @Override
    public int attempt() {
        return 1;
    }

    @Override
    public String idempotencyKey() {
        return "workflow-1:node-1:1";
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    @Override
    public Instant startedAt() {
        return Instant.now();
    }

    @Override
    public long timeoutMillis() {
        return 30_000;
    }

    // --- deliberately unreachable ---------------------------------------------------------------

    @Override
    public VariableView variables() {
        throw new UnsupportedOperationException("The email plugin reads variables through resolve()");
    }

    @Override
    public PluginDescriptor descriptor() {
        throw new UnsupportedOperationException("not used by the email plugin");
    }

    @Override
    public PluginSettings settings() {
        throw new UnsupportedOperationException("not used by the email plugin");
    }

    @Override
    public PluginHttpClient http() {
        throw new UnsupportedOperationException("an email node has no business making HTTP calls");
    }

    @Override
    public PluginDataStore dataStore() {
        throw new UnsupportedOperationException("not used by the email plugin");
    }

    @Override
    public IdempotencyStore idempotency() {
        throw new UnsupportedOperationException("not used by the email plugin");
    }

    @Override
    public PluginEventPublisher events() {
        throw new UnsupportedOperationException("not used by the email plugin");
    }

    @Override
    public Path workspace() {
        throw new UnsupportedOperationException("the email plugin writes no files");
    }

    /** Configuration backed by a map, which is what the engine hands a node once variables are resolved. */
    private record MapConfiguration(Map<String, Object> values) implements NodeConfiguration {

        @Override
        public Optional<Object> find(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Map<String, Object> asMap() {
            return values;
        }
    }
}
