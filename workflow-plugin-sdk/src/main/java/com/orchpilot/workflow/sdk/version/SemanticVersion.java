package com.orchpilot.workflow.sdk.version;

import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@code MAJOR.MINOR.PATCH} version with an optional pre-release tag, ordered by precedence.
 *
 * <p>In the SDK rather than in either service because both need to agree on it. The plugin server decides
 * which version is the latest; the workflow service decides whether an update is available. If those two used
 * different comparison rules, a plugin could be permanently "update available" and never reach parity.
 *
 * <h2>Why not compare the strings</h2>
 *
 * <p>Because {@code "1.10.0" < "1.9.0"} lexicographically, which is the wrong answer and the kind of wrong
 * answer that surfaces only after a project reaches its tenth minor release.
 *
 * <h2>Ordering</h2>
 *
 * <p>Numeric fields ascending, then a pre-release version is <em>lower</em> than the release it precedes
 * ({@code 1.2.0-rc.1} before {@code 1.2.0}), which is the rule from semver.org. Build metadata after {@code +}
 * is parsed, retained for display and ignored for ordering, also per the specification: two builds of the same
 * version are the same version.
 *
 * @since 1.0.0
 */
public final class SemanticVersion implements Comparable<SemanticVersion> {

    /** Ascending by precedence. Nulls sort first, so an unknown version never looks newest. */
    public static final Comparator<SemanticVersion> ASCENDING =
            Comparator.nullsFirst(Comparator.naturalOrder());

    /** Descending by precedence, for "latest first" listings. */
    public static final Comparator<SemanticVersion> DESCENDING = ASCENDING.reversed();

    private final int major;
    private final int minor;
    private final int patch;
    private final String preRelease;
    private final String build;

    private SemanticVersion(int major, int minor, int patch, String preRelease, String build) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.preRelease = preRelease;
        this.build = build;
    }

    /**
     * Parses a version, rejecting anything that is not a version.
     *
     * @param text candidate, for example {@code 1.2.0} or {@code 2.0.0-rc.1+build.7}
     * @return the parsed version
     * @throws IllegalArgumentException when the text is not a valid semantic version
     */
    public static SemanticVersion parse(String text) {
        return tryParse(text).orElseThrow(() -> new IllegalArgumentException(
                "'" + text + "' is not a semantic version. Expected MAJOR.MINOR.PATCH, "
                        + "optionally followed by -preRelease and +build."));
    }

    /**
     * Parses defensively.
     *
     * <p>Returns empty rather than throwing, because the caller is often reading a value written by an older
     * version of the platform or typed by a plugin author, and a malformed version should exclude a plugin
     * from consideration rather than fail the request that happened to list it.
     *
     * @param text candidate; may be {@code null}
     * @return the version, or empty when it cannot be parsed
     */
    public static Optional<SemanticVersion> tryParse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        // Tolerate a leading v, which people write out of habit and which means nothing here.
        if (value.charAt(0) == 'v' || value.charAt(0) == 'V') {
            value = value.substring(1);
        }

        String build = null;
        int plus = value.indexOf('+');
        if (plus >= 0) {
            build = emptyToNull(value.substring(plus + 1));
            value = value.substring(0, plus);
        }

        String preRelease = null;
        int dash = value.indexOf('-');
        if (dash >= 0) {
            preRelease = emptyToNull(value.substring(dash + 1));
            value = value.substring(0, dash);
        }

        String[] parts = value.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            int major = parseNumber(parts[0]);
            int minor = parseNumber(parts[1]);
            int patch = parseNumber(parts[2]);
            return Optional.of(new SemanticVersion(major, minor, patch, preRelease, build));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Compares two versions given as text.
     *
     * @param left  first version
     * @param right second version
     * @return negative when {@code left} is older, zero when equal in precedence, positive when newer
     */
    public static int compare(String left, String right) {
        return ASCENDING.compare(tryParse(left).orElse(null), tryParse(right).orElse(null));
    }

    /**
     * @param candidate version to test
     * @param current   version currently held
     * @return whether {@code candidate} is strictly newer than {@code current}
     */
    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    /** @return the pre-release tag, or empty for a release version */
    public Optional<String> preRelease() {
        return Optional.ofNullable(preRelease);
    }

    /** @return build metadata, which is retained for display and ignored for ordering */
    public Optional<String> build() {
        return Optional.ofNullable(build);
    }

    /** @return whether this is a pre-release, which is not a candidate for automatic latest resolution */
    public boolean isPreRelease() {
        return preRelease != null;
    }

    /**
     * Whether a change from this version to {@code other} is breaking under semantic versioning.
     *
     * <p>Used to decide whether an update needs a compatibility warning. A major bump is breaking by
     * definition; below 1.0.0 a minor bump is breaking too, because semver gives no stability guarantee
     * before the first release and plugin authors do use 0.x while a schema is still moving.
     *
     * @param other the version being moved to
     * @return whether the move may break existing configuration
     */
    public boolean isBreakingChangeTo(SemanticVersion other) {
        if (other == null) {
            return true;
        }
        if (major != other.major) {
            return true;
        }
        return major == 0 && minor != other.minor;
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(minor, other.minor);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(patch, other.patch);
        if (result != 0) {
            return result;
        }
        return comparePreRelease(preRelease, other.preRelease);
    }

    /**
     * Pre-release precedence.
     *
     * <p>Absent beats present: {@code 1.2.0} is newer than {@code 1.2.0-rc.1}. Two pre-releases compare
     * identifier by identifier, numerically where both identifiers are numeric, so {@code rc.2} beats
     * {@code rc.10} would be wrong and is not what happens here.
     */
    private static int comparePreRelease(String left, String right) {
        if (Objects.equals(left, right)) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        String[] leftParts = left.split("\\.", -1);
        String[] rightParts = right.split("\\.", -1);
        int shared = Math.min(leftParts.length, rightParts.length);
        for (int index = 0; index < shared; index++) {
            int result = comparePreReleaseIdentifier(leftParts[index], rightParts[index]);
            if (result != 0) {
                return result;
            }
        }
        // A longer pre-release with an otherwise equal prefix has higher precedence: rc.1.1 after rc.1.
        return Integer.compare(leftParts.length, rightParts.length);
    }

    private static int comparePreReleaseIdentifier(String left, String right) {
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) {
            return Integer.compare(Integer.parseInt(left), Integer.parseInt(right));
        }
        if (leftNumeric) {
            // Numeric identifiers always have lower precedence than alphanumeric ones.
            return -1;
        }
        if (rightNumeric) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static int parseNumber(String value) {
        if (value.isEmpty()) {
            throw new NumberFormatException("empty");
        }
        // Rejects "+1", "-1" and " 1", which Integer.parseInt would otherwise accept or mis-handle.
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                throw new NumberFormatException(value);
            }
        }
        return Integer.parseInt(value);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /**
     * @return the canonical text, which round-trips through {@link #parse(String)}
     */
    @Override
    public String toString() {
        StringBuilder text = new StringBuilder().append(major).append('.').append(minor)
                .append('.').append(patch);
        if (preRelease != null) {
            text.append('-').append(preRelease);
        }
        if (build != null) {
            text.append('+').append(build);
        }
        return text.toString();
    }

    /** Equality is precedence equality, so build metadata is excluded. */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SemanticVersion version)) {
            return false;
        }
        return major == version.major && minor == version.minor && patch == version.patch
                && Objects.equals(preRelease, version.preRelease);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, preRelease);
    }
}
