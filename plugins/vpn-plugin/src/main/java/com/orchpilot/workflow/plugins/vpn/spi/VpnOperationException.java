package com.orchpilot.workflow.plugins.vpn.spi;

/**
 * A provider's operation failed in a way worth classifying.
 *
 * <p>Carries a structured code and a retry decision, so the node can turn it into a workflow failure the
 * engine's retry and error policies understand without the node having to know one provider's failures from
 * another's. A provider throws this; the node classifies nothing further.
 */
public class VpnOperationException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    /**
     * @param code      a {@code VPN_*} code
     * @param message   a sentence for the execution record, never carrying a credential
     * @param retryable whether the engine should apply its backoff and try again
     */
    public VpnOperationException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public VpnOperationException(String code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
