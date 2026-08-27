package com.orchpilot.workflow.model;

/**
 * Per-node retry configuration.
 *
 * <p>Persisted as an embedded document. A node with no policy inherits {@link #disabled()}, so a
 * workflow author has to opt in to retrying: silently repeating a node that sends email is worse than
 * failing once.
 */
public class RetryPolicy {

    private boolean enabled;
    private int maxAttempts = 3;
    private long backoffMillis = 5_000;
    private double backoffMultiplier = 2.0;
    private long maxBackoffMillis = 60_000;

    public RetryPolicy() {
    }

    /**
     * @return a policy that never retries
     */
    public static RetryPolicy disabled() {
        RetryPolicy policy = new RetryPolicy();
        policy.enabled = false;
        policy.maxAttempts = 1;
        return policy;
    }

    /**
     * @param maxAttempts    total attempts including the first
     * @param backoffMillis  delay before the second attempt
     * @return an enabled policy with exponential backoff
     */
    public static RetryPolicy of(int maxAttempts, long backoffMillis) {
        RetryPolicy policy = new RetryPolicy();
        policy.enabled = true;
        policy.maxAttempts = Math.max(1, maxAttempts);
        policy.backoffMillis = Math.max(0, backoffMillis);
        return policy;
    }

    /**
     * @return total attempts permitted, at least one, and one when disabled
     */
    public int effectiveMaxAttempts() {
        return enabled ? Math.max(1, maxAttempts) : 1;
    }

    /**
     * Backoff before a given attempt, capped at {@link #getMaxBackoffMillis()}.
     *
     * @param nextAttempt 1-based number of the attempt about to be made; the first has no delay
     * @return milliseconds to wait
     */
    public long backoffFor(int nextAttempt) {
        if (!enabled || nextAttempt <= 1) {
            return 0;
        }
        double multiplier = backoffMultiplier <= 0 ? 1.0 : backoffMultiplier;
        double delay = backoffMillis * Math.pow(multiplier, nextAttempt - 2);
        long capped = Math.min((long) delay, maxBackoffMillis <= 0 ? Long.MAX_VALUE : maxBackoffMillis);
        return Math.max(0, capped);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getBackoffMillis() {
        return backoffMillis;
    }

    public void setBackoffMillis(long backoffMillis) {
        this.backoffMillis = backoffMillis;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(double backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public long getMaxBackoffMillis() {
        return maxBackoffMillis;
    }

    public void setMaxBackoffMillis(long maxBackoffMillis) {
        this.maxBackoffMillis = maxBackoffMillis;
    }
}
