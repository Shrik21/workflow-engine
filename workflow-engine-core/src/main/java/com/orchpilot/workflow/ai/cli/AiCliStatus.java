package com.orchpilot.workflow.ai.cli;

/**
 * What the last check of an AI CLI configuration found.
 *
 * <p>Stored on the configuration so the settings page can show a status without running the executable on every
 * page load — running a process to render a list would be both slow and a way to make the engine spawn
 * processes by browsing.
 */
public enum AiCliStatus {

    /** Never tested, or the path is blank. */
    NOT_CONFIGURED,

    /** The executable ran and reported a version. */
    CONNECTED,

    /** The executable could not be run, timed out, or exited non-zero. */
    ERROR
}
