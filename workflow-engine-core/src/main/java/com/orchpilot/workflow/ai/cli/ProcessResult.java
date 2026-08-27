package com.orchpilot.workflow.ai.cli;

/**
 * What one CLI invocation produced.
 *
 * @param exitCode  the process exit code; 0 conventionally means success
 * @param stdout    captured standard output, truncated to the configured cap
 * @param stderr    captured standard error, truncated to the configured cap
 * @param truncated whether either stream hit the cap, so a caller knows the output is incomplete
 * @param durationMillis wall-clock time the process ran
 */
public record ProcessResult(int exitCode, String stdout, String stderr, boolean truncated,
                            long durationMillis) {

    public boolean isSuccess() {
        return exitCode == 0;
    }

    /**
     * @return stderr when there is any, otherwise stdout — what to show when something went wrong, since CLIs
     *         disagree about which stream carries a diagnostic
     */
    public String diagnostic() {
        String error = stderr == null ? "" : stderr.trim();
        if (!error.isEmpty()) {
            return error;
        }
        return stdout == null ? "" : stdout.trim();
    }
}
