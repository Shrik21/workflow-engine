package com.orchpilot.workflow.sdk.plugin;

import java.util.Map;
import java.util.Set;

/**
 * A plugin that exposes named, callable operations which are not modelled as workflow nodes.
 *
 * <p>Useful for capabilities the engine or other plugins invoke directly rather than by placing a
 * node on a canvas: a credential test button in an administration screen, a lookup that populates a
 * dropdown at design time, or a maintenance routine.
 *
 * <p>Implementations must be thread-safe.
 *
 * @since 1.0.0
 */
public interface ActionPlugin extends WorkflowPlugin {

    /**
     * @return the action names {@link #invoke(String, Map)} accepts; used to publish the plugin's
     *         callable surface without invoking anything
     */
    Set<String> getSupportedActions();

    /**
     * Invokes one action.
     *
     * @param action     action name, one of {@link #getSupportedActions()}
     * @param parameters action parameters; never {@code null}, may be empty
     * @return the result, or {@code null} for actions with no return value
     * @throws com.orchpilot.workflow.sdk.exception.PluginException when the action fails or is unknown
     */
    Map<String, Object> invoke(String action, Map<String, Object> parameters);
}
