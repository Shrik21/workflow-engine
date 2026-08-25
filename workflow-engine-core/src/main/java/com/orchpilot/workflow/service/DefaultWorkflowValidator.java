package com.orchpilot.workflow.service;

import com.orchpilot.workflow.execution.WorkflowGraph;
import com.orchpilot.workflow.expression.ExpressionEvaluator;
import com.orchpilot.workflow.model.DecisionCondition;
import com.orchpilot.workflow.model.NodeTypes;
import com.orchpilot.workflow.model.PluginStatus;
import com.orchpilot.workflow.model.PluginVersion;
import com.orchpilot.workflow.model.TriggerType;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowConnection;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.model.WorkflowTrigger;
import com.orchpilot.workflow.node.WorkflowNodeRegistry;
import com.orchpilot.workflow.repository.PluginVersionRepository;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.plugin.PluginRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Graph, node, plugin and expression validation.
 *
 * <p>Every check exists because of a failure mode that is expensive to discover at runtime:
 *
 * <ul>
 *   <li>Structural checks (one start, at least one end, no dangling edges, no duplicate ids) catch graphs the
 *       engine physically cannot walk.</li>
 *   <li>Plugin checks catch a workflow that references a plugin version which is not installed or not active.
 *       Finding this at publish time is the difference between a validation message and a production
 *       execution failing at its third node.</li>
 *   <li>Required-configuration checks use the schema the plugin itself published, so the engine validates
 *       integrations it knows nothing about.</li>
 *   <li>Expression checks parse every decision condition and edge guard, catching typos and forbidden
 *       constructs before an execution ever evaluates them.</li>
 * </ul>
 *
 * <p>Cycles are reported as warnings rather than errors. A loop is sometimes exactly what an author wants,
 * such as a retry-with-approval pattern, and the engine's step ceiling already bounds it.
 */
@Service
public class DefaultWorkflowValidator implements WorkflowValidator {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowValidator.class);

    /** Any one of these means somebody has been named, in either the terse or the explicit spelling. */
    private static final List<String> ASSIGNMENT_KEYS = List.of("assignee", "assigneeUserId",
            "assigneeUsername", "candidateUsers", "candidateUserIds", "candidateGroups", "candidateGroupIds");

    private final WorkflowNodeRegistry nodeRegistry;
    private final PluginRegistry pluginRegistry;
    private final PluginVersionRepository pluginVersionRepository;
    private final ExpressionEvaluator expressionEvaluator;
    private final com.orchpilot.workflow.forms.FormNodeBinding formNodeBinding;
    private final com.orchpilot.workflow.pluginserver.PluginPublishPolicy publishPolicy;

    public DefaultWorkflowValidator(WorkflowNodeRegistry nodeRegistry, PluginRegistry pluginRegistry,
                                    PluginVersionRepository pluginVersionRepository,
                                    ExpressionEvaluator expressionEvaluator,
                                    com.orchpilot.workflow.forms.FormNodeBinding formNodeBinding,
                                    com.orchpilot.workflow.pluginserver.PluginPublishPolicy publishPolicy) {
        this.nodeRegistry = nodeRegistry;
        this.pluginRegistry = pluginRegistry;
        this.pluginVersionRepository = pluginVersionRepository;
        this.expressionEvaluator = expressionEvaluator;
        this.formNodeBinding = formNodeBinding;
        this.publishPolicy = publishPolicy;
    }

    @Override
    public List<String> validate(Workflow workflow) {
        List<String> errors = new ArrayList<>();
        if (workflow == null) {
            return List.of("No workflow was supplied");
        }
        if (workflow.getName() == null || workflow.getName().isBlank()) {
            errors.add("The workflow must have a name");
        }
        if (workflow.getNodes().isEmpty()) {
            errors.add("The workflow has no nodes");
            return errors;
        }

        validateNodeIdentity(workflow, errors);
        validateStartAndEnd(workflow, errors);
        WorkflowGraph graph = WorkflowGraph.of(workflow.getNodes(), workflow.getConnections());
        validateConnections(workflow, graph, errors);
        validateReachability(workflow, graph, errors);
        for (WorkflowNode node : workflow.getNodes()) {
            validateNode(node, graph, errors);
        }
        validateTriggers(workflow, errors);
        log.debug("Validated workflow {} ({} node(s)): {} problem(s)", workflow.getId(),
                workflow.getNodes().size(), errors.size());
        return errors;
    }

    @Override
    public List<String> warnings(Workflow workflow) {
        List<String> warnings = new ArrayList<>();
        if (workflow == null || workflow.getNodes().isEmpty()) {
            return warnings;
        }
        WorkflowGraph graph = WorkflowGraph.of(workflow.getNodes(), workflow.getConnections());
        List<String> cycle = findCycle(graph, workflow.getNodes());
        if (!cycle.isEmpty()) {
            warnings.add("The graph contains a cycle: " + String.join(" -> ", cycle)
                    + ". Execution is bounded by the engine's step limit, so make sure a condition eventually "
                    + "breaks the loop.");
        }
        for (WorkflowNode node : workflow.getNodes()) {
            if (node.isPluginNode() && (node.getPluginVersion() == null || node.getPluginVersion().isBlank())) {
                warnings.add("Node '" + node.getId() + "' does not pin a plugin version, so it will follow the "
                        + "plugin's default version and its behaviour can change when a new version is "
                        + "uploaded.");
            }
            if (node.isPluginNode()) {
                // Deprecated upstream, or superseded by something already installed here. Neither blocks a
                // publish: both describe a plugin that works.
                warnings.addAll(publishPolicy.warnings(node));
            }
            if (NodeTypes.END.equals(node.getType()) && !graph.outgoing(node.getId()).isEmpty()) {
                warnings.add("End node '" + node.getId() + "' has outgoing connections, which are ignored.");
            }
            if (NodeTypes.FORM.equals(node.getType())) {
                warnAboutFormNode(node, warnings);
            }
        }
        return warnings;
    }

    /**
     * Two things about a human step that are worth saying before it runs.
     *
     * <p>Warnings rather than errors, both of them:
     *
     * <ul>
     *   <li><b>The form does not resolve.</b> Form nodes predate the form designer, so {@code formId} may
     *       legitimately be an arbitrary string that the node's own configuration describes. Refusing to publish
     *       a workflow that has been running for months would be the wrong way to introduce this check.</li>
     *   <li><b>Nobody is addressed.</b> A task naming no assignee and no candidate group is raised, sits where
     *       only an administrator can find it, and blocks the run. That is nearly always a mistake, but "the
     *       administrator does it by hand" is a real, if unusual, way to work.</li>
     * </ul>
     */
    private void warnAboutFormNode(WorkflowNode node, List<String> warnings) {
        Map<String, Object> configuration = node.getConfiguration();
        String formId = formNodeBinding.formIdOf(node, configuration);
        if (formNodeBinding.resolve(node, configuration).isEmpty()) {
            warnings.add("Form node '" + node.getId() + "' references form '" + formId
                    + "', which has no published version. The task will be raised with nothing to fill in "
                    + "until the form is published.");
        }
        boolean addressed = ASSIGNMENT_KEYS.stream().anyMatch(key -> {
            Object value = configuration.get(key);
            return value != null && !String.valueOf(value).isBlank() && !"[]".equals(String.valueOf(value));
        });
        if (!addressed) {
            warnings.add("Form node '" + node.getId() + "' names no assignee and no candidate group, so the "
                    + "task it raises will be visible only to an administrator.");
        }
    }

    // ----------------------------------------------------------------- checks

    private void validateNodeIdentity(Workflow workflow, List<String> errors) {
        Set<String> seen = new HashSet<>();
        for (WorkflowNode node : workflow.getNodes()) {
            if (node.getId() == null || node.getId().isBlank()) {
                errors.add("Every node must have an id");
                continue;
            }
            if (!seen.add(node.getId())) {
                errors.add("Duplicate node id '" + node.getId() + "'");
            }
            if (node.getType() == null || node.getType().isBlank()) {
                errors.add("Node '" + node.getId() + "' has no type");
            }
        }
    }

    private void validateStartAndEnd(Workflow workflow, List<String> errors) {
        long startCount = workflow.getNodes().stream()
                .filter(node -> NodeTypes.START.equals(node.getType())).count();
        long endCount = workflow.getNodes().stream()
                .filter(node -> NodeTypes.END.equals(node.getType())).count();
        if (startCount != 1) {
            errors.add("The workflow must have exactly one START node but has " + startCount);
        }
        if (endCount < 1) {
            errors.add("The workflow must have at least one END node");
        }
    }

    private void validateConnections(Workflow workflow, WorkflowGraph graph, List<String> errors) {
        for (WorkflowConnection connection : workflow.getConnections()) {
            if (connection.getSource() == null || connection.getSource().isBlank()) {
                errors.add("A connection has no source");
                continue;
            }
            if (connection.getTarget() == null || connection.getTarget().isBlank()) {
                errors.add("Connection from '" + connection.getSource() + "' has no target");
                continue;
            }
            if (graph.node(connection.getSource()).isEmpty()) {
                errors.add("Connection source '" + connection.getSource() + "' is not a node in this workflow");
            }
            if (graph.node(connection.getTarget()).isEmpty()) {
                errors.add("Connection target '" + connection.getTarget() + "' is not a node in this workflow");
            }
            if (connection.getSource().equals(connection.getTarget())) {
                errors.add("Connection on '" + connection.getSource() + "' points at itself");
            }
            // Nothing may connect into a START node — it is where execution begins, never a destination. This
            // is also what stops a decision branch (or any edge) being pointed at Start.
            graph.node(connection.getTarget())
                    .filter(target -> NodeTypes.START.equals(target.getType()))
                    .ifPresent(target -> errors.add("Connection from '" + connection.getSource()
                            + "' points at the START node '" + target.getId()
                            + "', but nothing may connect into a START node"));
            if (connection.getCondition() != null && !connection.getCondition().isBlank()) {
                validateExpression(connection.getCondition(),
                        "guard on connection " + connection.getSource() + " -> " + connection.getTarget(),
                        errors);
            }
        }
    }

    private void validateReachability(Workflow workflow, WorkflowGraph graph, List<String> errors) {
        // A compensation node is reached by the error policy of the node it compensates, not by an edge, so it is
        // legitimately without incoming connections. Treating it as an orphan would make COMPENSATE unusable.
        Set<String> compensationTargets = new HashSet<>();
        for (WorkflowNode node : workflow.getNodes()) {
            if (node.getCompensationNodeId() != null && !node.getCompensationNodeId().isBlank()) {
                compensationTargets.add(node.getCompensationNodeId());
            }
        }
        for (WorkflowNode node : workflow.getNodes()) {
            if (node.getId() == null) {
                continue;
            }
            if (graph.isOrphan(node.getId()) && !compensationTargets.contains(node.getId())) {
                errors.add("Node '" + node.getId() + "' is unreachable: nothing connects to it, and it is not "
                        + "referenced as a compensationNodeId");
            }
            boolean needsExit = !NodeTypes.END.equals(node.getType());
            if (needsExit && graph.outgoing(node.getId()).isEmpty()) {
                errors.add("Node '" + node.getId() + "' has no outgoing connection and is not an END node");
            }
        }
    }

    private void validateNode(WorkflowNode node, WorkflowGraph graph, List<String> errors) {
        if (node.getId() == null || node.getType() == null) {
            return;
        }
        if (node.isPluginNode()) {
            validatePluginNode(node, errors);
            // What the registry says, which local state cannot know: a revoked plugin is installed, ACTIVE and
            // loaded, so every check above passes while its publisher is telling every engine to stop using it.
            errors.addAll(publishPolicy.errors(node));
            return;
        }
        if (!nodeRegistry.canResolve(node)) {
            errors.add("Node '" + node.getId() + "' has type '" + node.getType()
                    + "', which no executor or loaded plugin provides. Known types: "
                    + nodeRegistry.knownNodeTypes());
            return;
        }
        if (NodeTypes.DECISION.equals(node.getType())) {
            validateDecisionNode(node, graph, errors);
        }
        if (NodeTypes.FORM.equals(node.getType())) {
            validateFormNode(node, errors);
        }
    }

    /**
     * Checks that a form node names a form and that a person could be found to answer it.
     *
     * <p>Both are publish-time findings for problems that would otherwise appear at run time, one node into a
     * live execution, as a task sitting in nobody's inbox.
     *
     * <p>An unresolvable {@code formId} is a warning rather than an error, not out of timidity: form nodes predate
     * the form designer, so the id may legitimately be an arbitrary string that the node's own configuration
     * describes, and refusing to publish a workflow that has been running for months would be the wrong way to
     * introduce this check.
     */
    private void validateFormNode(WorkflowNode node, List<String> errors) {
        if ((node.getFormId() == null || node.getFormId().isBlank())
                && !node.getConfiguration().containsKey("formId")) {
            errors.add("Form node '" + node.getId() + "' must declare a formId");
        }
    }

    private void validateDecisionNode(WorkflowNode node, WorkflowGraph graph, List<String> errors) {
        List<DecisionCondition> conditions = node.getConditions();
        boolean hasDefault = node.getDefaultBranch() != null && !node.getDefaultBranch().isBlank();
        if (conditions.isEmpty() && !node.getConfiguration().containsKey("conditions") && !hasDefault) {
            errors.add("Decision node '" + node.getId() + "' declares neither conditions nor a default branch");
            return;
        }
        Set<String> declaredPorts = new LinkedHashSet<>();
        for (WorkflowConnection connection : graph.outgoing(node.getId())) {
            if (connection.getSourcePort() != null && !connection.getSourcePort().isBlank()) {
                declaredPorts.add(connection.getSourcePort());
            }
        }
        // A condition repeated verbatim can never be reached past its first appearance, so the designer forbids
        // it and publish rejects it — "duplicate decision condition is not allowed".
        Set<String> seenExpressions = new LinkedHashSet<>();
        for (DecisionCondition condition : conditions) {
            String expression = condition.getExpression() == null ? "" : condition.getExpression().trim();
            if (!expression.isEmpty() && !seenExpressions.add(expression)) {
                errors.add("Decision node '" + node.getId() + "' has a duplicate condition '" + expression
                        + "'; the branch that repeats it can never be reached");
            }
        }
        for (DecisionCondition condition : conditions) {
            if (condition.getBranch() == null || condition.getBranch().isBlank()) {
                errors.add("A condition on decision node '" + node.getId() + "' has no branch name");
                continue;
            }
            validateExpression(condition.getExpression(),
                    "condition '" + condition.getBranch() + "' on node " + node.getId(), errors);
            if (!declaredPorts.contains(condition.getBranch())
                    && graph.outgoing(node.getId()).stream().noneMatch(WorkflowConnection::isDefaultEdge)) {
                errors.add("Decision node '" + node.getId() + "' has a branch '" + condition.getBranch()
                        + "' with no matching connection and no default connection to fall back on");
            }
        }
    }

    private void validateExpression(String expression, String where, List<String> errors) {
        if (expression == null || expression.isBlank()) {
            errors.add("The " + where + " has no expression");
            return;
        }
        try {
            expressionEvaluator.validate(expression);
        } catch (RuntimeException ex) {
            errors.add("The " + where + " is not a valid expression: " + ex.getMessage());
        }
    }

    /**
     * Checks a plugin node against what is actually installed.
     *
     * <p>Deliberately consults both the live registry and the persisted version documents. The registry says
     * what can execute right now; the documents say what is installed. A version that is installed but
     * inactive produces a specific, actionable message instead of "unknown node type".
     */
    private void validatePluginNode(WorkflowNode node, List<String> errors) {
        String pluginId = node.getPluginId();
        String version = node.getPluginVersion();

        if (pluginId == null || pluginId.isBlank()) {
            if (pluginRegistry.findByNodeType(node.getType()).isEmpty()) {
                errors.add("Node '" + node.getId() + "' has plugin type '" + node.getType()
                        + "' but no loaded plugin provides it");
            }
            return;
        }

        if (version == null || version.isBlank()) {
            if (pluginRegistry.findDefault(pluginId).isEmpty()) {
                errors.add("Node '" + node.getId() + "' references plugin '" + pluginId
                        + "', which has no loaded version");
            }
            return;
        }

        Optional<PluginVersion> installed = pluginVersionRepository.findByPluginIdAndVersion(pluginId, version);
        if (installed.isEmpty()) {
            errors.add("Node '" + node.getId() + "' references plugin '" + pluginId + "' version '" + version
                    + "', which is not installed");
            return;
        }
        PluginVersion metadata = installed.get();
        if (metadata.getStatus() != PluginStatus.ACTIVE) {
            errors.add("Node '" + node.getId() + "' references plugin '" + pluginId + ":" + version
                    + "', which is " + metadata.getStatus() + " rather than ACTIVE");
            return;
        }
        if (pluginRegistry.find(pluginId, version).isEmpty()) {
            errors.add("Plugin '" + pluginId + ":" + version + "' is marked ACTIVE but is not loaded"
                    + (metadata.getLoadError() == null ? "" : ": " + metadata.getLoadError()));
            return;
        }
        validateRequiredConfiguration(node, pluginId, version, errors);
    }

    /**
     * Validates the node's configuration against the schema the plugin published.
     *
     * <p>The engine has no idea what a SendGrid node needs. The plugin does, and it says so in its
     * configuration schema, so this check works for integrations written long after the engine shipped.
     */
    @SuppressWarnings("unchecked")
    private void validateRequiredConfiguration(WorkflowNode node, String pluginId, String version,
                                               List<String> errors) {
        Optional<NodeDefinition> definition = pluginRegistry.find(pluginId, version)
                .flatMap(handle -> handle.nodeTypes().size() == 1 && NodeTypes.PLUGIN.equals(node.getType())
                        ? handle.nodeDefinition(handle.nodeTypes().get(0))
                        : handle.nodeDefinition(node.getType()));
        if (definition.isEmpty()) {
            return;
        }
        Map<String, Object> schema = definition.get().configurationSchema();
        Map<String, Object> configuration = node.getConfiguration();
        String nodeType = definition.get().nodeType();

        Object required = schema.get("required");
        if (required instanceof List) {
            for (Object item : (List<Object>) required) {
                String key = String.valueOf(item);
                if (isMissing(configuration.get(key))) {
                    errors.add("Node '" + node.getId() + "' is missing required configuration '" + key
                            + "' for node type '" + nodeType + "'");
                }
            }
        }
        validateConditionallyRequired(node, schema, configuration, nodeType, errors);
    }

    /**
     * Enforces {@code requiredWhen}, which makes a property required only for certain values of another.
     *
     * <p>The unconditional {@code required} list above cannot express this. A node with an operation selector
     * has fields that matter for one operation and are meaningless for the rest; putting such a field in
     * {@code required} would refuse every node that chose a different operation, so plugins were forced to
     * declare nothing required and check during execution instead. That works, but it reports the mistake
     * after the workflow is published and running, which is the expensive place to find it.
     *
     * <p>Checked here rather than trusting the designer, for the same reason every other rule is: the panel
     * is a convenience and this is the authority.
     */
    @SuppressWarnings("unchecked")
    private void validateConditionallyRequired(WorkflowNode node, Map<String, Object> schema,
                                               Map<String, Object> configuration, String nodeType,
                                               List<String> errors) {
        Object properties = schema.get("properties");
        if (!(properties instanceof Map)) {
            return;
        }
        for (Map.Entry<String, Object> property : ((Map<String, Object>) properties).entrySet()) {
            if (!(property.getValue() instanceof Map)) {
                continue;
            }
            Object condition = ((Map<String, Object>) property.getValue()).get("requiredWhen");
            if (!(condition instanceof Map)) {
                continue;
            }
            if (conditionHolds((Map<String, Object>) condition, configuration)
                    && isMissing(configuration.get(property.getKey()))) {
                errors.add("Node '" + node.getId() + "' is missing configuration '" + property.getKey()
                        + "', which node type '" + nodeType + "' requires for "
                        + describe((Map<String, Object>) condition));
            }
        }
    }

    /**
     * @return whether every named field holds one of its listed values, so a condition can depend on two
     *         selectors at once
     */
    @SuppressWarnings("unchecked")
    private static boolean conditionHolds(Map<String, Object> condition, Map<String, Object> configuration) {
        for (Map.Entry<String, Object> entry : condition.entrySet()) {
            Object held = configuration.get(entry.getKey());
            if (held == null || String.valueOf(held).isEmpty()) {
                return false;
            }
            if (!(entry.getValue() instanceof List)) {
                return false;
            }
            boolean matched = false;
            for (Object allowed : (List<Object>) entry.getValue()) {
                if (String.valueOf(allowed).equals(String.valueOf(held))) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    /** Renders the condition so the message says which choice made the field necessary. */
    private static String describe(Map<String, Object> condition) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Object> entry : condition.entrySet()) {
            if (!text.isEmpty()) {
                text.append(" and ");
            }
            text.append(entry.getKey()).append(" = ").append(entry.getValue());
        }
        return text.toString();
    }

    private static boolean isMissing(Object value) {
        return value == null || (value instanceof CharSequence && ((CharSequence) value).isEmpty());
    }

    private void validateTriggers(Workflow workflow, List<String> errors) {
        Set<String> triggerIds = new HashSet<>();
        for (WorkflowTrigger trigger : workflow.getTriggers()) {
            if (trigger.getId() == null || trigger.getId().isBlank()) {
                errors.add("Every trigger must have an id");
                continue;
            }
            if (!triggerIds.add(trigger.getId())) {
                errors.add("Duplicate trigger id '" + trigger.getId() + "'");
            }
            if (trigger.getType() == TriggerType.SCHEDULE) {
                if (trigger.getCron() == null || trigger.getCron().isBlank()) {
                    errors.add("Schedule trigger '" + trigger.getId() + "' has no cron expression");
                } else if (!CronExpression.isValidExpression(trigger.getCron())) {
                    errors.add("Schedule trigger '" + trigger.getId() + "' has an invalid cron expression '"
                            + trigger.getCron() + "'. Spring cron has six fields: second minute hour "
                            + "day-of-month month day-of-week.");
                }
                if (trigger.getTimezone() != null && !trigger.getTimezone().isBlank()) {
                    try {
                        java.time.ZoneId.of(trigger.getTimezone());
                    } catch (RuntimeException ex) {
                        errors.add("Schedule trigger '" + trigger.getId() + "' has an unknown timezone '"
                                + trigger.getTimezone() + "'");
                    }
                }
            }
            if (trigger.getType() == TriggerType.EVENT
                    && (trigger.getEventName() == null || trigger.getEventName().isBlank())) {
                errors.add("Event trigger '" + trigger.getId() + "' has no event name");
            }
        }
    }

    /**
     * Iterative depth-first cycle detection.
     *
     * <p>Iterative rather than recursive because a validator must not overflow the stack on a
     * pathologically long chain, which is exactly the kind of input a generated workflow produces.
     *
     * @return the nodes forming a cycle, or an empty list
     */
    private List<String> findCycle(WorkflowGraph graph, List<WorkflowNode> nodes) {
        Set<String> permanentlyDone = new HashSet<>();
        for (WorkflowNode start : nodes) {
            if (start.getId() == null || permanentlyDone.contains(start.getId())) {
                continue;
            }
            Deque<String> path = new ArrayDeque<>();
            Set<String> onPath = new LinkedHashSet<>();
            Deque<java.util.Iterator<WorkflowConnection>> iterators = new ArrayDeque<>();

            path.push(start.getId());
            onPath.add(start.getId());
            iterators.push(graph.outgoing(start.getId()).iterator());

            while (!path.isEmpty()) {
                java.util.Iterator<WorkflowConnection> edges = iterators.peek();
                if (edges.hasNext()) {
                    String target = edges.next().getTarget();
                    if (target == null || permanentlyDone.contains(target)) {
                        continue;
                    }
                    if (onPath.contains(target)) {
                        List<String> cycle = new ArrayList<>(onPath);
                        cycle.add(target);
                        return cycle;
                    }
                    path.push(target);
                    onPath.add(target);
                    iterators.push(graph.outgoing(target).iterator());
                } else {
                    String finished = path.pop();
                    iterators.pop();
                    onPath.remove(finished);
                    permanentlyDone.add(finished);
                }
            }
        }
        return List.of();
    }
}
