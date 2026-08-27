package com.orchpilot.workflow.plugin;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.execution.WorkflowExecutionContext;
import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.model.PluginExecutionRecord;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.node.WorkflowNodeExecutor;
import com.orchpilot.workflow.plugin.context.ExecutionScopedFileAccess;
import com.orchpilot.workflow.plugin.context.PluginNodeExecutionContext;
import com.orchpilot.workflow.plugin.context.SecretRedactor;
import com.orchpilot.workflow.repository.PluginExecutionRepository;
import com.orchpilot.workflow.sdk.exception.PluginSecurityException;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;
import com.orchpilot.workflow.storage.service.WorkflowFileStorageService;
import com.orchpilot.workflow.utility.HashUtils;
import com.orchpilot.workflow.variable.VariableResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single bridge between the engine and every plugin.
 *
 * <p>This class is the reason the engine contains no integration-specific code. It is the only place that
 * knows a plugin exists, and it does five things around each invocation:
 *
 * <ol>
 *   <li><b>Version resolution.</b> A node that pins {@code pluginVersion} gets exactly that version; one
 *       that does not gets the current default. Pinning is honoured strictly, because the promise that
 *       publishing a workflow freezes its behaviour depends on it.</li>
 *   <li><b>Leasing.</b> An invocation acquires a lease on the plugin handle so a concurrent unload waits
 *       instead of pulling the class loader out from under a running node.</li>
 *   <li><b>Idempotency.</b> For nodes not declared idempotent, a prior successful invocation with the same
 *       key is replayed from {@code plugin_executions} instead of being repeated. This is what makes retry
 *       and crash recovery safe for a node that sends email.</li>
 *   <li><b>Configuration resolution.</b> The plugin receives fully resolved values and never sees a
 *       {@code ${...}} template or a raw credential.</li>
 *   <li><b>Recording.</b> Request, response, duration and outcome are written for audit, after redaction and
 *       truncation.</li>
 * </ol>
 */
@Component
public class PluginNodeExecutor implements WorkflowNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(PluginNodeExecutor.class);

    private final PluginRegistry pluginRegistry;
    private final PluginExecutionRepository executionRepository;
    private final WorkflowEngineProperties properties;
    /** Supplies each attempt an accessor bound to the executing workflow; see ExecutionScopedFileAccess. */
    private final WorkflowFileStorageService fileStorageService;

    public PluginNodeExecutor(PluginRegistry pluginRegistry, PluginExecutionRepository executionRepository,
                              WorkflowEngineProperties properties,
                              WorkflowFileStorageService fileStorageService) {
        this.pluginRegistry = pluginRegistry;
        this.executionRepository = executionRepository;
        this.properties = properties;
        this.fileStorageService = fileStorageService;
    }

    @Override
    public String getNodeType() {
        return NodeTypes.PLUGIN;
    }

    @Override
    public NodeExecutionResult execute(WorkflowNode node, WorkflowExecutionContext context) {
        Optional<PluginHandle> resolved = resolveHandle(node);
        if (resolved.isEmpty()) {
            return NodeExecutionResult.failure("PLUGIN_NOT_AVAILABLE", describeMissing(node));
        }
        PluginHandle handle = resolved.get();

        Optional<WorkflowNodePlugin> nodePlugin = handle.asNodePlugin();
        if (nodePlugin.isEmpty()) {
            return NodeExecutionResult.failure("PLUGIN_NOT_A_NODE_PLUGIN",
                    "Plugin '" + handle.coordinate() + "' does not contribute node types");
        }

        // Resolved before the lease is taken: there is no point holding a plugin open for a node that is not
        // going to run, and an early return after acquiring would skip the release in the finally below.
        //
        // Reported rather than merely resolved: an unresolved ${...} is left literal by design, and a plugin
        // that then hands "${gcpProjectId}" to a cloud API or a secret store fails a long way from the cause.
        // The author reads an error about a project or a secret that does not exist, when what actually
        // happened is that they referenced a variable the workflow never declared.
        VariableResolver.Resolution resolution;
        try {
            resolution = context.resolveConfigurationReporting(node, secretLookup(handle));
        } catch (PluginSecurityException ex) {
            // A ${SECRET.x} the plugin was not granted. Reported as the node's failure with the provider's own
            // message, which names the plugin and its declared scopes.
            return NodeExecutionResult.failure("SECRET_ACCESS_DENIED", ex.getMessage());
        }
        if (!resolution.isComplete()) {
            return unresolvedVariables(node, resolution.unresolved());
        }
        Map<String, Object> configuration = resolution.configuration();

        if (!handle.tryAcquireLease()) {
            // Retryable on purpose: the plugin is being drained or reloaded, and the same node may well
            // succeed once the new version is registered.
            return NodeExecutionResult.failure("PLUGIN_UNAVAILABLE",
                    "Plugin '" + handle.coordinate() + "' is not accepting work (state " + handle.state()
                            + ")", true);
        }

        String nodeType = effectiveNodeType(node, handle);
        String idempotencyKey = idempotencyKey(context, node, handle, configuration);
        NodeDefinition definition = handle.nodeDefinition(nodeType).orElse(null);
        boolean guarded = definition != null && !definition.idempotent();

        try {
            if (guarded) {
                Optional<NodeExecutionResult> replay = replayPreviousSuccess(idempotencyKey, context, node);
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            return invoke(nodePlugin.get(), handle, node, nodeType, configuration, idempotencyKey,
                    guarded, context);
        } finally {
            handle.releaseLease();
        }
    }

    // ------------------------------------------------------------- invocation

    private NodeExecutionResult invoke(WorkflowNodePlugin plugin, PluginHandle handle, WorkflowNode node,
                                       String nodeType, Map<String, Object> configuration,
                                       String idempotencyKey, boolean guarded,
                                       WorkflowExecutionContext context) {
        long timeout = node.getTimeoutMillis() == null
                ? properties.getExecution().getDefaultNodeTimeoutMillis()
                : node.getTimeoutMillis();

        PluginNodeExecutionContext pluginContext = new PluginNodeExecutionContext(context, node, nodeType,
                configuration, handle.pluginContext(), idempotencyKey, context.currentAttempt(), timeout,
                new ExecutionScopedFileAccess(fileStorageService, context.workflowId(),
                        context.workflowVersion(), handle.pluginId()));

        Instant start = Instant.now();
        NodeExecutionResult result;
        // The context class loader is swapped for the duration of the call. Libraries a plugin bundles
        // commonly use it for their own service discovery, and leaving the engine's loader in place makes
        // those libraries fail in ways that look like plugin bugs.
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(handle.classLoader());
        try {
            result = plugin.execute(pluginContext);
            if (result == null) {
                result = NodeExecutionResult.failure("PLUGIN_RETURNED_NULL",
                        "Plugin '" + handle.coordinate() + "' returned no result");
            }
        } catch (RuntimeException | LinkageError ex) {
            handle.recordFailure();
            // Rethrow as a failure result rather than propagating: the retry template and error policy are
            // the engine's, and a plugin throwing must not look different from a plugin failing.
            result = NodeExecutionResult.failure(errorCodeFor(ex), safeMessage(ex), isRetryable(ex));
            log.warn("Plugin {} node {} threw {}: {}", handle.coordinate(), node.getId(),
                    ex.getClass().getSimpleName(), ex.getMessage());
            record(handle, node, nodeType, context, idempotencyKey, configuration, result, start, guarded);
            return result;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        if (result.isFailed()) {
            handle.recordFailure();
        }
        record(handle, node, nodeType, context, idempotencyKey, configuration, result, start, guarded);
        return result;
    }

    /**
     * Replays a previous successful invocation instead of repeating it.
     *
     * <p>This is the mechanism that stops a retry, or a resume after a crash, from sending a second email.
     * It only applies to nodes whose definition says they are not idempotent, because for a plugin that is
     * safe to repeat, replaying a stale response would be worse than calling again.
     */
    private Optional<NodeExecutionResult> replayPreviousSuccess(String idempotencyKey,
                                                               WorkflowExecutionContext context,
                                                               WorkflowNode node) {
        Optional<PluginExecutionRecord> previous = executionRepository.findByIdempotencyKey(idempotencyKey);
        if (previous.isEmpty() || !"SUCCESS".equals(previous.get().getStatus())) {
            return Optional.empty();
        }
        PluginExecutionRecord record = previous.get();
        Map<String, Object> outputs = new LinkedHashMap<>(record.getResponse());
        outputs.put("__replayed", Boolean.TRUE);
        outputs.put("__originalExecutedAt", record.getStartTime() == null ? null
                : record.getStartTime().toString());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("idempotencyKey", idempotencyKey);
        details.put("originalRecordId", record.getId());
        context.logInfo(node.getId(), node.getType(),
                "Replaying the recorded result of a previous successful invocation instead of calling the "
                        + "plugin again", details);
        log.info("Node {} of execution {} replayed idempotent result for key {}", node.getId(),
                context.executionId(), idempotencyKey);
        return Optional.of(NodeExecutionResult.success(outputs));
    }

    // -------------------------------------------------------------- recording

    private void record(PluginHandle handle, WorkflowNode node, String nodeType,
                        WorkflowExecutionContext context, String idempotencyKey,
                        Map<String, Object> configuration, NodeExecutionResult result, Instant start,
                        boolean guarded) {
        WorkflowEngineProperties.Plugins config = properties.getPlugins();
        SecretRedactor redactor = handle.pluginContext().redactor();

        PluginExecutionRecord record = new PluginExecutionRecord();
        record.setExecutionId(context.executionId());
        record.setWorkflowId(context.workflowId());
        record.setWorkflowVersion(context.workflowVersion());
        record.setNodeId(node.getId());
        record.setNodeType(nodeType);
        record.setPluginId(handle.pluginId());
        record.setPluginVersion(handle.version());
        // Only guarded nodes claim the unique key. An idempotent node writing it would make its own second
        // attempt collide for no benefit.
        record.setIdempotencyKey(guarded ? idempotencyKey : null);
        record.setAttempt(context.currentAttempt());
        record.setStatus(result.status().name());
        record.setStartTime(start);
        record.setEndTime(Instant.now());
        record.setDurationMillis(Duration.between(start, Instant.now()).toMillis());
        record.setErrorCode(result.errorCode());
        record.setErrorMessage(truncate(redactor.redact(result.errorMessage()), config.getPayloadMaxChars()));
        if (config.isRecordPayloads()) {
            record.setRequest(bounded(redactor.redactMap(configuration), config.getPayloadMaxChars()));
            record.setResponse(bounded(redactor.redactMap(result.outputs()), config.getPayloadMaxChars()));
        } else {
            record.setResponse(bounded(redactor.redactMap(result.outputs()), config.getPayloadMaxChars()));
        }

        try {
            executionRepository.save(record);
        } catch (DuplicateKeyException ex) {
            // Another attempt claimed the same idempotency key concurrently. The other attempt's record
            // stands; losing this race is the guard working, not a failure.
            log.info("Plugin execution record for key {} already exists; keeping the existing one",
                    idempotencyKey);
        } catch (RuntimeException ex) {
            log.warn("Could not persist plugin execution record for node {}: {}", node.getId(),
                    ex.getMessage());
        }
    }

    // --------------------------------------------------------------- helpers

    /**
     * Resolves which loaded plugin version serves this node.
     *
     * <p>Three ways a node can name a plugin, in order: an explicit id and version, an explicit id with no
     * version, or neither, in which case the node's own type is looked up in the plugin registry's node type
     * index. The third form is what lets a designer drop a {@code SENDGRID_EMAIL} node on a canvas without
     * knowing which plugin provides it.
     */
    /**
     * The secret lookup backing {@code ${SECRET.name}} for one plugin invocation.
     *
     * <h2>Two properties this gets for free, and one it adds</h2>
     *
     * It delegates to the plugin's own {@code ScopedSecretProvider}, so a secret reference is subject to
     * exactly the scopes granted at upload — a plugin holding {@code gcp.} cannot reach {@code stripe.}
     * through a variable any more than through the API — and every read is audited by that provider just as a
     * direct one is.
     *
     * <p>What it adds is registering the value with this invocation's redactor. The resolved configuration is
     * written to the plugin execution record, and without this the secret would be stored there in clear.
     */
    private VariableResolver.SecretLookup secretLookup(PluginHandle handle) {
        SecretRedactor redactor = handle.pluginContext().redactor();
        return name -> {
            Optional<String> value = handle.pluginContext().secrets().find(name);
            value.ifPresent(redactor::remember);
            return value;
        };
    }

    /**
     * Fails the node because its configuration referenced variables that do not exist.
     *
     * <p>Not retryable: a variable that was not declared will not appear on a second attempt.
     *
     * <p>A value that is meant to contain literal {@code ${...}} text — a template body, a shell snippet —
     * escapes it as {@code $${...}}, which resolves to the literal and is not reported here. So this only
     * fires on a reference that was genuinely intended to be a variable.
     */
    private NodeExecutionResult unresolvedVariables(WorkflowNode node,
                                                    List<VariableResolver.UnresolvedReference> unresolved) {
        String detail = unresolved.stream()
                .map(VariableResolver.UnresolvedReference::toString)
                .collect(java.util.stream.Collectors.joining(", "));
        String names = unresolved.stream()
                .map(VariableResolver.UnresolvedReference::variable)
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));

        log.warn("Node {} was not run: unresolved variable reference(s) {}", node.getId(), detail);

        return NodeExecutionResult.failure("UNRESOLVED_VARIABLE",
                "Node '" + node.getId() + "' was not run because its configuration references "
                        + (unresolved.size() == 1 ? "a variable that does not exist"
                                : "variables that do not exist")
                        + ": " + detail + ". Declare " + names + " in the workflow's variables, supply "
                        + "it as input when starting the run, or map it from an earlier node's output. "
                        + "Remember to republish after changing a workflow's variables — a run uses the "
                        + "published version, not the draft.");
    }

    private Optional<PluginHandle> resolveHandle(WorkflowNode node) {
        String pluginId = node.getPluginId();
        String version = node.getPluginVersion();
        if (pluginId != null && !pluginId.isBlank()) {
            if (version != null && !version.isBlank()) {
                return pluginRegistry.find(pluginId, version);
            }
            return pluginRegistry.findDefault(pluginId);
        }
        return pluginRegistry.findByNodeType(node.getType());
    }

    private String describeMissing(WorkflowNode node) {
        if (node.getPluginId() != null && !node.getPluginId().isBlank()) {
            String version = node.getPluginVersion() == null || node.getPluginVersion().isBlank()
                    ? "(default)" : node.getPluginVersion();
            return "Plugin '" + node.getPluginId() + "' version " + version + " is not loaded. Check that "
                    + "the version is installed and ACTIVE.";
        }
        return "No loaded plugin provides node type '" + node.getType() + "'";
    }

    /**
     * A node declared as the generic {@code PLUGIN} type executes the plugin's sole node type. Any other
     * type is taken at face value, which is how a multi-node plugin's individual types are addressed.
     */
    private String effectiveNodeType(WorkflowNode node, PluginHandle handle) {
        if (!NodeTypes.PLUGIN.equals(node.getType())) {
            return node.getType();
        }
        return handle.nodeTypes().size() == 1 ? handle.nodeTypes().get(0) : NodeTypes.PLUGIN;
    }

    /**
     * Builds the deterministic key for this node's side effect.
     *
     * <p>Includes the execution, the node, the plugin coordinate and a fingerprint of the resolved
     * configuration. Including the configuration means a node whose input genuinely changed, in a workflow
     * that loops back over it, is treated as a distinct operation rather than being wrongly deduplicated.
     * The fingerprint sorts map keys, so a round trip through MongoDB cannot change the key.
     */
    private String idempotencyKey(WorkflowExecutionContext context, WorkflowNode node, PluginHandle handle,
                                  Map<String, Object> configuration) {
        String material = context.executionId() + "|" + node.getId() + "|" + handle.coordinate() + "|"
                + HashUtils.fingerprint(configuration);
        return HashUtils.sha256Hex(material);
    }

    private static String errorCodeFor(Throwable ex) {
        if (ex instanceof com.orchpilot.workflow.sdk.exception.PluginException) {
            return ((com.orchpilot.workflow.sdk.exception.PluginException) ex).getErrorCode();
        }
        if (ex instanceof LinkageError) {
            return "PLUGIN_LINKAGE_ERROR";
        }
        return "PLUGIN_EXECUTION_ERROR";
    }

    private static boolean isRetryable(Throwable ex) {
        return ex instanceof com.orchpilot.workflow.sdk.exception.PluginException
                && ((com.orchpilot.workflow.sdk.exception.PluginException) ex).isRetryable();
    }

    private static String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private static String truncate(String value, int max) {
        if (value == null || max <= 0 || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...[truncated]";
    }

    private static Map<String, Object> bounded(Map<String, Object> source, int maxChars) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> {
            if (value instanceof CharSequence) {
                copy.put(key, truncate(value.toString(), maxChars));
            } else {
                copy.put(key, value);
            }
        });
        return copy;
    }
}
