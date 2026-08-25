package com.orchpilot.workflow.plugins.vpn.support;

import com.orchpilot.workflow.sdk.context.HttpRequestSpec;
import com.orchpilot.workflow.sdk.context.HttpResponseView;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** The engine services the VPN plugin uses: logger, secrets, HTTP client, resolved configuration, resolve. */
public final class TestExecution implements NodeExecutionContext, PluginContext {

    private final NodeConfiguration configuration;
    private final Map<String, String> secrets;
    private final Map<String, String> variables;
    private final Function<HttpRequestSpec, HttpResponseView> http;
    private final List<String> logLines = new ArrayList<>();
    private volatile boolean cancelled;

    private TestExecution(Map<String, Object> configuration, Map<String, String> secrets,
                          Map<String, String> variables, Function<HttpRequestSpec, HttpResponseView> http) {
        this.configuration = new MapConfiguration(configuration);
        this.secrets = secrets;
        this.variables = variables;
        this.http = http;
    }

    public static Builder with(Map<String, Object> configuration) {
        return new Builder(configuration);
    }

    public List<String> logLines() {
        return logLines;
    }

    public void cancel() {
        cancelled = true;
    }

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
    public PluginHttpClient http() {
        return request -> {
            if (http == null) {
                throw new UnsupportedOperationException("no HTTP client configured for this test");
            }
            return http.apply(request);
        };
    }

    @Override
    public PluginLogger logger() {
        return new PluginLogger() {
            @Override
            public void debug(String message, Object... args) {
                logLines.add(format(message, args));
            }

            @Override
            public void info(String message, Object... args) {
                logLines.add(format(message, args));
            }

            @Override
            public void warn(String message, Object... args) {
                logLines.add(format(message, args));
            }

            @Override
            public void error(String message, Object... args) {
                logLines.add(format(message, args));
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

    private static String format(String message, Object... args) {
        String result = message;
        for (Object argument : args) {
            result = result.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(
                    String.valueOf(argument)));
        }
        return result;
    }

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
        return "vpn-1";
    }

    @Override
    public String nodeType() {
        return "VPN";
    }

    @Override
    public int attempt() {
        return 1;
    }

    @Override
    public String idempotencyKey() {
        return "workflow-1:vpn-1:1";
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public Instant startedAt() {
        return Instant.now();
    }

    @Override
    public long timeoutMillis() {
        return 300_000;
    }

    @Override
    public VariableView variables() {
        throw new UnsupportedOperationException("the VPN plugin reads variables through resolve()");
    }

    @Override
    public PluginSettings settings() {
        return new PluginSettings() {
            @Override
            public Optional<Object> find(String key) {
                return Optional.empty();
            }

            @Override
            public Map<String, Object> asMap() {
                return Map.of();
            }
        };
    }

    @Override
    public PluginDescriptor descriptor() {
        throw new UnsupportedOperationException("not used");
    }

    @Override
    public PluginDataStore dataStore() {
        throw new UnsupportedOperationException("the VPN plugin does not use the engine data store");
    }

    @Override
    public IdempotencyStore idempotency() {
        throw new UnsupportedOperationException("not used");
    }

    @Override
    public PluginEventPublisher events() {
        throw new UnsupportedOperationException("not used");
    }

    @Override
    public Path workspace() {
        throw new UnsupportedOperationException("the VPN plugin writes no files");
    }

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

    public static final class Builder {
        private final Map<String, Object> configuration;
        private Map<String, String> secrets = new LinkedHashMap<>();
        private Map<String, String> variables = new LinkedHashMap<>();
        private Function<HttpRequestSpec, HttpResponseView> http;

        private Builder(Map<String, Object> configuration) {
            this.configuration = configuration;
        }

        public Builder secrets(Map<String, String> values) {
            this.secrets = values;
            return this;
        }

        public Builder variables(Map<String, String> values) {
            this.variables = values;
            return this;
        }

        public Builder http(Function<HttpRequestSpec, HttpResponseView> handler) {
            this.http = handler;
            return this;
        }

        public TestExecution build() {
            return new TestExecution(configuration, secrets, variables, http);
        }
    }
}
