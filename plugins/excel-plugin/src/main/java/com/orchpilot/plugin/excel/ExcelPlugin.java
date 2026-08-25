package com.orchpilot.plugin.excel;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.plugin.excel.model.ExcelOperation;
import com.orchpilot.workflow.sdk.context.PluginContext;
import com.orchpilot.workflow.sdk.context.WorkflowFileAccess;
import com.orchpilot.workflow.sdk.exception.PluginConfigurationException;
import com.orchpilot.workflow.sdk.exception.PluginException;
import com.orchpilot.workflow.sdk.node.NodeDefinition;
import com.orchpilot.workflow.sdk.node.NodeExecutionContext;
import com.orchpilot.workflow.sdk.node.NodeExecutionResult;
import com.orchpilot.workflow.sdk.plugin.PluginType;
import com.orchpilot.workflow.sdk.plugin.WorkflowNodePlugin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel workbooks as OrchPilot workflow nodes and AI Agent tools.
 *
 * <h2>How this stays inside the existing platform</h2>
 *
 * Nothing here duplicates the platform. There is no second AI agent, plugin server, registry, security system,
 * workflow engine or file store — this is one {@link WorkflowNodePlugin} reaching the outside world only
 * through the SDK. Configuration arrives already variable-resolved by the engine's own resolver, so
 * {@code ${department}} is substituted before this code sees it, and every authorization decision stays the
 * engine's.
 *
 * <h2>Files, never paths</h2>
 *
 * Every workbook is addressed by a file id and reached through
 * {@link NodeExecutionContext#files()} — an accessor the engine binds to the executing workflow and version.
 * This plugin cannot name another workflow's file, because the API has no argument for it, and it never learns
 * where any file physically lives. There is no code path here that touches the filesystem.
 *
 * <h2>The AI Agent boundary</h2>
 *
 * Nodes declare {@code supportsAI}, so the existing agent discovers them through the existing Plugin Registry
 * as ordinary tools. What it discovers is a fixed catalogue of typed operations — never a formula evaluator,
 * never a scripting hook, never a path. The agent selects a tool and supplies parameters; the engine authorises
 * it; only then does this plugin open a file the agent never had access to.
 *
 * <p>Thread-safe: the plugin object holds only the context, and each execution builds its own
 * {@link ExcelOperations}.
 */
public class ExcelPlugin implements WorkflowNodePlugin {

    private static final String PLUGIN_ID = "orchpilot-excel-handler";
    private static final String PLUGIN_VERSION = "1.0.3";
    private static final String CATEGORY = "Excel";

    private volatile PluginContext context;

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return "Excel Handler";
    }

    @Override
    public String getVersion() {
        return PLUGIN_VERSION;
    }

    @Override
    public String getDescription() {
        return "Read, write, search, transform, validate, merge, split, compare and report on Excel workbooks.";
    }

    @Override
    public PluginType getPluginType() {
        return PluginType.NODE;
    }

    @Override
    public void initialize(PluginContext pluginContext) throws PluginException {
        this.context = pluginContext;
        pluginContext.logger().info("Excel Handler plugin initialised with {} operations",
                ExcelOperation.values().length);
    }

    @Override
    public void destroy() {
        if (context != null) {
            context.logger().info("Excel Handler plugin destroyed");
        }
    }

    // ------------------------------------------------------------------ node catalogue

    @Override
    public List<NodeDefinition> getNodeDefinitions() {
        List<NodeDefinition> definitions = new ArrayList<>(ExcelOperation.values().length);
        for (ExcelOperation operation : ExcelOperation.values()) {
            definitions.add(NodeDefinition.builder(operation.nodeType())
                    .displayName(operation.displayName())
                    .description(operation.description() + " [capability: " + operation.capability() + "]")
                    .category(CATEGORY)
                    .icon("table")
                    .configurationSchema(NodeSchemas.forOperation(operation))
                    .outputVariables("success", "operation", "sheet", "rowCount", "columnCount", "headers",
                            "data", "fileId", "fileName", "checksum", "valid", "errors")
                    // A read is repeatable; anything producing a file is not, because a retry would store a
                    // second copy. The engine's idempotency guard replays instead.
                    .idempotent(!operation.producesFile())
                    .supportsRetry(true)
                    .supportsAI(true)
                    .destructive(operation.destructive())
                    .build());
        }
        return definitions;
    }

    // ------------------------------------------------------------------ execution

    @Override
    public NodeExecutionResult execute(NodeExecutionContext executionContext) {
        ExcelOperation operation = ExcelOperation.forNodeType(executionContext.nodeType());
        if (operation == null) {
            return NodeExecutionResult.failure("EXCEL_UNKNOWN_OPERATION",
                    "Unknown Excel node type: " + executionContext.nodeType());
        }

        WorkflowFileAccess files;
        try {
            files = executionContext.files();
        } catch (UnsupportedOperationException ex) {
            // The engine predates plugin file access. Said plainly, because the alternative is a
            // NullPointerException from somewhere deep in the operation.
            return NodeExecutionResult.failure("EXCEL_STORAGE_ERROR",
                    "This OrchPilot engine does not provide workflow file access to plugins, which the Excel "
                            + "Handler requires. Upgrade the engine.");
        }

        long startedAt = System.currentTimeMillis();
        try {
            NodeExecutionResult result =
                    new ExcelOperations(operation, executionContext.configuration(), files).run();
            audit(executionContext, operation, result.isSuccess() ? "SUCCESS" : "FAILED", startedAt);
            return result;
        } catch (ExcelException ex) {
            context.logger().warn("Excel {} failed: {} ({})", operation, ex.errorCode(), ex.getMessage());
            audit(executionContext, operation, "FAILED", startedAt);
            return NodeExecutionResult.failure(ex.errorCode(), ex.getMessage(), ex.retryable());
        } catch (PluginConfigurationException ex) {
            return NodeExecutionResult.failure("EXCEL_INVALID_DATA", ex.getMessage());
        } catch (OutOfMemoryError ex) {
            // Caught deliberately. A workbook that defeats the limits should fail this node with an
            // actionable message rather than destabilising an engine running every other workflow.
            context.logger().error("Excel {} exhausted memory; the limits were not sufficient", operation);
            return NodeExecutionResult.failure("EXCEL_FILE_TOO_LARGE",
                    "The workbook needed more memory than is available. Lower 'maxRows' and 'maxColumns', or "
                            + "split the file before processing it.", false);
        }
    }

    /**
     * Records what happened, through the plugin's own data store.
     *
     * <p>Metadata only: the operation, the coordinates and the outcome. Never a cell, a row or a file's
     * contents — an audit trail that carried spreadsheet data would be a copy of the data it is auditing, in a
     * place with different retention and different access control.
     *
     * <p>Best-effort: a failed audit write must not fail an operation that already succeeded.
     */
    private void audit(NodeExecutionContext ctx, ExcelOperation operation, String outcome, long startedAt) {
        try {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("pluginId", PLUGIN_ID);
            record.put("operation", operation.name());
            record.put("capability", operation.capability());
            record.put("riskLevel", operation.risk().name());
            record.put("workflowId", ctx.workflowId());
            record.put("workflowVersion", ctx.workflowVersion());
            record.put("workflowExecutionId", ctx.executionId());
            record.put("nodeId", ctx.nodeId());
            ctx.currentUser().ifPresent(user -> {
                record.put("userId", user.userId());
                record.put("username", user.username());
            });
            record.put("fileId", ctx.configuration().getString("fileId", null));
            record.put("outcome", outcome);
            record.put("durationMillis", System.currentTimeMillis() - startedAt);
            record.put("timestamp", Instant.now().toString());
            context.dataStore().put("audit", ctx.executionId() + ":" + ctx.nodeId() + ":" + ctx.attempt(),
                    record);
        } catch (RuntimeException ex) {
            context.logger().warn("Could not write an Excel audit record: {}", ex.getMessage());
        }
    }
}
