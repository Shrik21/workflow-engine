package com.orchpilot.workflow.ai.tool;

import java.util.Set;

/**
 * Decides, for each tool an agent wants to call, whether it may run — the destructive-action gate.
 *
 * <h2>Autonomous vs supervised, plus a hard deny</h2>
 *
 * Two dials, evaluated the same way every time so the rule is easy to reason about:
 *
 * <ul>
 *   <li><b>Denied</b> tools never run, in any mode. This is the belt-and-braces block for a capability an operator
 *       wants an agent never to touch.</li>
 *   <li><b>Supervised</b> mode gates <em>destructive</em> tools (see {@link AITool#isDestructive()}): a destructive
 *       tool runs only if it has been approved, otherwise it is blocked and the model is told so. Non-destructive
 *       tools still run freely — supervision is about consequential actions, not read-only ones.</li>
 *   <li><b>Autonomous</b> mode runs everything that is not denied.</li>
 * </ul>
 *
 * <p>Approvals are supplied to the node at runtime — typically by an upstream human-task or Form node writing the
 * approved tool names to a workflow variable — so the approval flow composes from the platform's existing nodes
 * rather than a new suspend/resume path in the engine. A blocked call is never an error: the agent loop feeds the
 * block back to the model as data, so a supervised agent degrades to explaining or to safe actions rather than
 * failing the workflow.
 */
public final class ToolApprovalPolicy {

    private final boolean supervised;
    private final Set<String> approved;
    private final Set<String> denied;

    public ToolApprovalPolicy(boolean supervised, Set<String> approved, Set<String> denied) {
        this.supervised = supervised;
        this.approved = approved == null ? Set.of() : Set.copyOf(approved);
        this.denied = denied == null ? Set.of() : Set.copyOf(denied);
    }

    /** The default: run every tool that is not denied, no approvals required. */
    public static ToolApprovalPolicy autonomous() {
        return new ToolApprovalPolicy(false, Set.of(), Set.of());
    }

    /** A decision for one tool: whether it may run, and — when not — a reason the model can read. */
    public record Decision(boolean allowed, String reason) {
        static Decision allow() {
            return new Decision(true, null);
        }

        static Decision block(String reason) {
            return new Decision(false, reason);
        }
    }

    public Decision evaluate(AITool tool) {
        String name = tool.getName();
        if (denied.contains(name)) {
            return Decision.block("This tool is blocked by policy and was not run.");
        }
        if (supervised && tool.isDestructive() && !approved.contains(name)) {
            return Decision.block("This tool has consequential side effects and needs human approval, which was "
                    + "not granted, so it was not run.");
        }
        return Decision.allow();
    }
}
