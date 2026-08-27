package com.orchpilot.workflow.ai.cli;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Host-level controls for AI CLI execution.
 *
 * <h2>Why this exists on top of RBAC</h2>
 *
 * Every other setting in OrchPilot is configured through the UI. This one deliberately is not. Naming an
 * executable that the engine process will run is qualitatively different from configuring an HTTP endpoint: if
 * the permission model were ever misconfigured, a UI-only switch would be the difference between a wrong API
 * call and arbitrary code running as the engine's user.
 *
 * <p>So there are two independent gates. This one is set by whoever controls the host's configuration file and
 * cannot be changed from the application at all; the {@code AI_CLI_*} permissions govern who may use it once
 * the operator has allowed it. Turning it off stops execution everywhere, immediately, regardless of what is
 * stored in MongoDB.
 *
 * <p>Default is <b>off</b>. A feature that runs local binaries should be something an operator opted into.
 */
@Component
@ConfigurationProperties(prefix = "workflow.engine.ai.cli")
public class AiCliProperties {

    /** Master switch. Nothing is executed while this is false, whatever is configured or permitted. */
    private boolean enabled = false;

    /** How long any single CLI invocation may run before it is destroyed, in seconds. */
    private int timeoutSeconds = 120;

    /** Cap on captured stdout/stderr, in bytes. A runaway process must not exhaust the engine's heap. */
    private int maxOutputBytes = 512 * 1024;

    /**
     * Optional allowlist of directories an executable may live in.
     *
     * <p>Empty means "any path that passes validation", which is the practical default because installation
     * locations vary by machine. Setting it narrows the feature further for a hardened deployment: a path
     * outside every listed directory is refused no matter who configures it.
     */
    private List<String> allowedDirectories = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMaxOutputBytes() {
        return maxOutputBytes;
    }

    public void setMaxOutputBytes(int maxOutputBytes) {
        this.maxOutputBytes = maxOutputBytes;
    }

    public List<String> getAllowedDirectories() {
        return allowedDirectories;
    }

    public void setAllowedDirectories(List<String> allowedDirectories) {
        this.allowedDirectories = allowedDirectories == null ? new ArrayList<>() : allowedDirectories;
    }
}
