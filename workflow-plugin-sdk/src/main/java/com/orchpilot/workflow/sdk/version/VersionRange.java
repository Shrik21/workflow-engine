package com.orchpilot.workflow.sdk.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A conjunction of version constraints, such as {@code >=1.0.0 <2.0.0}.
 *
 * <p>Plugins declare which engine versions they work with, and something has to decide whether this engine
 * qualifies. In the SDK because both sides need the same answer: the registry can warn at publish time, and the
 * engine must refuse at install time, and a plugin that one accepts and the other rejects is worse than either
 * behaviour on its own.
 *
 * <h2>Deliberately small</h2>
 *
 * <p>Space-separated comparators, all of which must hold. No {@code ||}, no {@code ^}, no {@code ~}, no wildcards.
 * Those are conveniences for dependency resolvers choosing among candidates; this only ever answers one question
 * about one concrete version, and every additional operator is another way for two implementations to disagree.
 * A range this cannot parse is reported as unparseable rather than quietly treated as "anything goes".
 *
 * @since 1.0.0
 */
public final class VersionRange {

    private final List<Constraint> constraints;
    private final String text;

    private VersionRange(String text, List<Constraint> constraints) {
        this.text = text;
        this.constraints = List.copyOf(constraints);
    }

    /** One comparator, such as {@code >=1.0.0}. */
    private record Constraint(String operator, SemanticVersion version) {

        boolean allows(SemanticVersion candidate) {
            int comparison = candidate.compareTo(version);
            return switch (operator) {
                case ">=" -> comparison >= 0;
                case ">" -> comparison > 0;
                case "<=" -> comparison <= 0;
                case "<" -> comparison < 0;
                case "=", "==" -> comparison == 0;
                case "!=" -> comparison != 0;
                default -> false;
            };
        }
    }

    /**
     * Parses a range.
     *
     * @param text the range, for example {@code >=1.0.0 <2.0.0}, or a bare version meaning exactly that version
     * @return the range, or empty when it cannot be parsed
     */
    public static Optional<VersionRange> tryParse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        List<Constraint> constraints = new ArrayList<>();
        for (String token : text.trim().split("[\\s,]+")) {
            if (token.isEmpty()) {
                continue;
            }
            String operator = operatorOf(token);
            String versionText = token.substring(operator.length()).trim();
            // A bare version is an equality constraint, which is what somebody writing "1.0.0" means.
            String effectiveOperator = operator.isEmpty() ? "=" : operator;
            Optional<SemanticVersion> version = SemanticVersion.tryParse(versionText);
            if (version.isEmpty()) {
                return Optional.empty();
            }
            constraints.add(new Constraint(effectiveOperator, version.get()));
        }
        return constraints.isEmpty() ? Optional.empty()
                : Optional.of(new VersionRange(text.trim(), constraints));
    }

    private static String operatorOf(String token) {
        for (String candidate : List.of(">=", "<=", "==", "!=", ">", "<", "=")) {
            if (token.startsWith(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    /**
     * @param version the version to test
     * @return whether every constraint holds
     */
    public boolean allows(String version) {
        return SemanticVersion.tryParse(version).map(this::allows).orElse(false);
    }

    /**
     * @param version the version to test
     * @return whether every constraint holds
     */
    public boolean allows(SemanticVersion version) {
        if (version == null) {
            return false;
        }
        for (Constraint constraint : constraints) {
            if (!constraint.allows(version)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a version satisfies a range, treating an absent range as unconstrained.
     *
     * <p>An absent range means the plugin author said nothing, which is not the same as an unparseable one: the
     * first is a plugin that makes no claim, the second is a plugin whose claim nobody can evaluate. Absent
     * passes; unparseable fails, and the caller reports why.
     *
     * @param range   the declared range, may be null or blank
     * @param version the version to test
     * @return whether the version is acceptable
     */
    public static boolean satisfies(String range, String version) {
        if (range == null || range.isBlank()) {
            return true;
        }
        return tryParse(range).map(parsed -> parsed.allows(version)).orElse(false);
    }

    @Override
    public String toString() {
        return text;
    }
}
