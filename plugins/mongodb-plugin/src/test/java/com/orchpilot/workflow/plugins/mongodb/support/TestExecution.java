package com.orchpilot.workflow.plugins.mongodb.support;

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
import com.orchpilot.workflow.sdk.node.WorkflowUser;
import com.orchpilot.workflow.sdk.plugin.PluginDescriptor;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The engine services the MongoDB plugin actually uses, and nothing else.
 *
 * <p>Only a logger, installation settings, a secret store, resolved configuration and {@code resolve} are
 * reachable from the plugin's code path. The rest of {@link PluginContext} throws if touched, which is a test
 * in itself: this plugin has a MongoDB driver of its own and must never reach the engine's data store, and a
 * silent stub would let that go unnoticed.
 */
public final class TestExecution implements NodeExecutionContext, PluginContext {

    private final NodeConfiguration configuration;
    private final Map<String, Object> settings;
    private final Map<String, String> secrets;
    private final Map<String, String> variables;
    private final WorkflowUser user;
    private final List<String> logLines = new ArrayList<>();

    private TestExecution(Map<String, Object> configuration, Map<String, Object> settings,
                          Map<String, String> secrets, Map<String, String> variables, WorkflowUser user) {
        this.configuration = new MapConfiguration(configuration);
        this.settings = settings;
        this.secrets = secrets;
        this.variables = variables;
        this.user = user;
    }

    public static Builder with(Map<String, Object> configuration) {
        return new Builder(configuration);
    }

    public static TestExecution of(Map<String, Object> configuration) {
        return new Builder(configuration).build();
    }

    /** Everything logged, so a test can assert that a password or a filter never reached a log line. */
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
    public PluginSettings settings() {
        return new PluginSettings() {
            @Override
            public Optional<Object> find(String key) {
                return Optional.ofNullable(settings.get(key));
            }

            @Override
            public Map<String, Object> asMap() {
                return settings;
            }
        };
    }

    @Override
    public Optional<WorkflowUser> currentUser() {
        return Optional.ofNullable(user);
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

    /** SLF4J-style {} substitution, so an assertion sees the line as it would be written. */
    private static String format(String message, Object... args) {
        String result = message;
        for (Object argument : args) {
            result = result.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(
                    String.valueOf(argument)));
        }
        return result;
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
        return "MONGODB_READ";
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
        throw new UnsupportedOperationException("the MongoDB plugin reads variables through resolve()");
    }

    @Override
    public PluginDescriptor descriptor() {
        throw new UnsupportedOperationException("not used by the MongoDB plugin");
    }

    @Override
    public PluginHttpClient http() {
        throw new UnsupportedOperationException("a database node has no business making HTTP calls");
    }

    @Override
    public PluginDataStore dataStore() {
        throw new UnsupportedOperationException(
                "this plugin has its own driver and must not reach the engine's database");
    }

    @Override
    public IdempotencyStore idempotency() {
        throw new UnsupportedOperationException("not used by the MongoDB plugin");
    }

    @Override
    public PluginEventPublisher events() {
        throw new UnsupportedOperationException("not used by the MongoDB plugin");
    }

    @Override
    public Path workspace() {
        throw new UnsupportedOperationException("the MongoDB plugin writes no files");
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

    /** Builds one, since most tests vary only one thing about it. */
    public static final class Builder {
        private final Map<String, Object> configuration;
        private Map<String, Object> settings = new LinkedHashMap<>();
        private Map<String, String> secrets = new LinkedHashMap<>();
        private Map<String, String> variables = new LinkedHashMap<>();
        private WorkflowUser user;

        private Builder(Map<String, Object> configuration) {
            this.configuration = configuration;
        }

        public Builder settings(Map<String, Object> values) {
            this.settings = values;
            return this;
        }

        public Builder secrets(Map<String, String> values) {
            this.secrets = values;
            return this;
        }

        public Builder variables(Map<String, String> values) {
            this.variables = values;
            return this;
        }

        public Builder user(String username, String... roles) {
            this.user = new WorkflowUser("user-1", username, Set.of(roles));
            return this;
        }

        public TestExecution build() {
            return new TestExecution(configuration, settings, secrets, variables, user);
        }
    }
}
