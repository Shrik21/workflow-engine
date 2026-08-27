package com.orchpilot.workflow.variable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Substitutes {@code ${...}} placeholders in node configuration.
 *
 * <p>Kept behind an interface because resolution is the contract plugins depend on most tightly: a
 * plugin receives configuration with placeholders already replaced and never learns how that happened.
 */
public interface VariableResolver {

    /**
     * Supplies secret values for {@code ${SECRET.name}} references.
     *
     * <h2>Why this is a parameter rather than a scope in the {@link VariableStore}</h2>
     *
     * Every other scope lives in the store, and the store is snapshotted into the execution document on every
     * step. A secret placed there would be written to MongoDB in clear, for the life of the execution — so
     * secrets are deliberately not storable, and are fetched at the moment of resolution instead.
     *
     * <p>It is also a parameter because <em>which</em> secrets are readable differs per node: the
     * implementation passed by the engine is the invoking plugin's own scoped provider, so a plugin granted
     * {@code gcp.} cannot reach {@code stripe.} through a variable any more than it could through the API.
     */
    @FunctionalInterface
    interface SecretLookup {

        /** Denies everything. The default wherever no plugin scope applies, e.g. built-in nodes. */
        SecretLookup NONE = name -> Optional.empty();

        /**
         * @param name the secret's name
         * @return its value, or empty when it does not exist or is out of scope
         * @throws com.orchpilot.workflow.sdk.exception.PluginSecurityException when the caller may not read it
         */
        Optional<String> find(String name);
    }

    /**
     * Resolves placeholders anywhere in a value tree.
     *
     * <p>Maps and lists are walked recursively. A string that is exactly one placeholder keeps the
     * referenced value's type, so {@code "${amount}"} yields the number {@code 15000} rather than the
     * text {@code "15000"}; a string with surrounding text is rendered as text.
     *
     * @param value map, list, string or scalar
     * @param store variables to resolve against
     * @return a resolved copy; the input is never mutated
     */
    Object resolve(Object value, VariableStore store);

    /**
     * @param template text possibly containing placeholders
     * @param store    variables to resolve against
     * @return the rendered text, or {@code null} when {@code template} is {@code null}
     */
    String resolveText(String template, VariableStore store);

    /**
     * @param configuration node configuration, possibly containing placeholders
     * @param store         variables to resolve against
     * @return a fully resolved copy of the configuration
     */
    Map<String, Object> resolveConfiguration(Map<String, Object> configuration, VariableStore store);

    /**
     * One placeholder that referred to nothing.
     *
     * @param field    dotted path to the configuration entry that contained it, e.g. {@code project} or
     *                 {@code secondaryIpRanges[0].ipCidrRange}
     * @param variable the variable path that could not be found, e.g. {@code gcpProjectId}
     */
    record UnresolvedReference(String field, String variable) {

        @Override
        public String toString() {
            return "'" + field + "' references ${" + variable + "}";
        }
    }

    /**
     * The result of resolving a configuration, together with anything that could not be resolved.
     *
     * @param configuration the resolved copy
     * @param unresolved    every placeholder that referred to nothing, in encounter order
     */
    record Resolution(Map<String, Object> configuration, List<UnresolvedReference> unresolved) {

        public Resolution {
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
        }

        public boolean isComplete() {
            return unresolved.isEmpty();
        }
    }

    /**
     * Resolves a configuration and reports what it could not resolve.
     *
     * <h2>Why this exists alongside {@link #resolveConfiguration}</h2>
     *
     * An unresolved placeholder is left literal, which is the right default — substituting an empty string
     * would silently send a request to the wrong place. But a caller that then hands {@code ${gcpProjectId}}
     * to a cloud API or a secret store produces a failure a long way from its cause, and the author is left
     * reading an error about a secret that does not exist rather than about the variable they never declared.
     *
     * <p>An escaped literal — {@code $${x}} — is <em>not</em> reported. That is the whole reason this is
     * computed during resolution rather than by scanning the result for {@code ${}} afterwards: after
     * resolution the two are textually identical, so a later scan cannot tell a deliberate literal from a
     * typo.
     *
     * @param configuration node configuration, possibly containing placeholders
     * @param store         variables to resolve against
     * @return the resolved configuration and any unresolved references
     */
    Resolution resolveConfigurationReporting(Map<String, Object> configuration, VariableStore store);

    /**
     * Resolves a configuration, additionally allowing {@code ${SECRET.name}} references.
     *
     * <h2>What a secret reference is, and is not</h2>
     *
     * {@code ${SECRET.gcpProjectId}} substitutes the <em>value</em> of the secret named {@code gcpProjectId}
     * into the configuration the plugin receives. That is a genuine widening of the platform's usual rule that
     * a workflow stores only secret <em>names</em>, so it is deliberately narrow:
     *
     * <ul>
     *   <li><b>Explicit only.</b> The prefix is required. An unqualified {@code ${gcpProjectId}} searches the
     *       workflow, input, output and system scopes and never secrets, so no existing expression can start
     *       resolving to a credential because someone created a secret with a matching name.</li>
     *   <li><b>Never shadowed.</b> A secret reference consults only the secret store, so a workflow variable
     *       of the same name cannot intercept it.</li>
     *   <li><b>Scope-enforced.</b> {@code secrets} is the caller's own scoped provider, so the plugin's
     *       granted prefixes apply exactly as they do to {@code context.secrets()}.</li>
     *   <li><b>Never stored.</b> The value is fetched here and lives only in the resolved configuration for
     *       the length of the call. It never enters the variable store and so never reaches the persisted
     *       execution document.</li>
     * </ul>
     *
     * <p>The caller remains responsible for registering what it resolved with the invocation's redactor, so
     * the value is masked in the execution record.
     *
     * @param configuration node configuration, possibly containing placeholders
     * @param store         variables to resolve against
     * @param secrets       supplies secret values; {@link SecretLookup#NONE} denies all
     * @return the resolved configuration and any unresolved references
     */
    Resolution resolveConfigurationReporting(Map<String, Object> configuration, VariableStore store,
                                             SecretLookup secrets);

    /**
     * @param path a variable path
     * @return the secret name when {@code path} is a {@code SECRET.} reference, otherwise null
     */
    static String secretReference(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        // Accepted in any case because this scope is new: no existing expression can change meaning, and
        // "SECRET." is what reads naturally in a configuration field.
        if (trimmed.length() <= 7 || !trimmed.regionMatches(true, 0, "SECRET.", 0, 7)) {
            return null;
        }
        String name = trimmed.substring(7).trim();
        return name.isEmpty() ? null : name;
    }
}
