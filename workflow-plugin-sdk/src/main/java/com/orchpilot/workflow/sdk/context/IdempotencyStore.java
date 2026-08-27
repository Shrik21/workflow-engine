package com.orchpilot.workflow.sdk.context;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Cross-attempt deduplication for plugins that cause external side effects.
 *
 * <p>The engine already guards whole-node re-execution using
 * {@link com.orchpilot.workflow.sdk.node.NodeExecutionContext#idempotencyKey()}. This store exists for
 * plugins that perform several distinct side effects within one node, or that need a finer key than
 * the node grain, for example one key per recipient in a batch send.
 *
 * <p>Typical use:
 * <pre>{@code
 * String key = ctx.idempotencyKey() + ":" + recipient;
 * Optional<Map<String, Object>> done = store.lookup(key);
 * if (done.isPresent()) {
 *     return NodeExecutionResult.success(done.get());
 * }
 * if (!store.claim(key, Duration.ofMinutes(10))) {
 *     return NodeExecutionResult.failure("IN_FLIGHT", "Another attempt is in progress", true);
 * }
 * Map<String, Object> result = send(recipient);
 * store.complete(key, result);
 * }</pre>
 *
 * @since 1.0.0
 */
public interface IdempotencyStore {

    /**
     * @param key idempotency key
     * @return the stored result of a previously completed operation, or empty
     */
    Optional<Map<String, Object>> lookup(String key);

    /**
     * Atomically reserves a key so that concurrent or duplicate attempts do not both proceed.
     *
     * @param key idempotency key
     * @param ttl how long the claim is held before it is considered abandoned
     * @return {@code true} when the caller now owns the key, {@code false} when someone else does
     */
    boolean claim(String key, Duration ttl);

    /**
     * Marks a claimed key as completed and stores its result for future {@link #lookup(String)}.
     *
     * @param key    idempotency key
     * @param result outcome to replay on subsequent attempts; must not contain secrets
     */
    void complete(String key, Map<String, Object> result);

    /**
     * Releases a claimed key so a later attempt may retry.
     *
     * @param key       idempotency key
     * @param errorCode stable failure identifier
     * @param message   human-readable description
     */
    void release(String key, String errorCode, String message);
}
