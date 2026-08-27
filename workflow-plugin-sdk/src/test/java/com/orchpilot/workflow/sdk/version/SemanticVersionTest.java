package com.orchpilot.workflow.sdk.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version precedence, which two services have to agree on.
 *
 * <p>The plugin server picks the latest version and the workflow service decides whether an update exists. If
 * they disagreed, a plugin would either sit permanently on "update available" or never offer one.
 */
class SemanticVersionTest {

    @Nested
    @DisplayName("Parsing")
    class Parsing {

        @Test
        @DisplayName("reads major, minor and patch")
        void readsParts() {
            SemanticVersion version = SemanticVersion.parse("1.2.3");

            assertEquals(1, version.major());
            assertEquals(2, version.minor());
            assertEquals(3, version.patch());
            assertFalse(version.isPreRelease());
        }

        @Test
        @DisplayName("reads a pre-release tag and build metadata")
        void readsPreReleaseAndBuild() {
            SemanticVersion version = SemanticVersion.parse("2.0.0-rc.1+build.7");

            assertEquals("rc.1", version.preRelease().orElseThrow());
            assertEquals("build.7", version.build().orElseThrow());
            assertTrue(version.isPreRelease());
            assertEquals("2.0.0-rc.1+build.7", version.toString(), "canonical text round-trips");
        }

        @Test
        @DisplayName("tolerates a leading v, which people write out of habit")
        void toleratesLeadingV() {
            assertEquals(SemanticVersion.parse("1.0.0"), SemanticVersion.parse("v1.0.0"));
        }

        @Test
        @DisplayName("refuses things that are not versions")
        void refusesNonVersions() {
            for (String bad : List.of("1", "1.2", "1.2.3.4", "1.2.x", "", "latest", "1.-2.0", "1.+2.0")) {
                assertTrue(SemanticVersion.tryParse(bad).isEmpty(), "should refuse '" + bad + "'");
            }
            assertTrue(SemanticVersion.tryParse(null).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("latest"));
        }
    }

    @Nested
    @DisplayName("Precedence")
    class Precedence {

        @Test
        @DisplayName("orders numerically, so 1.10.0 is newer than 1.9.0")
        void ordersNumerically() {
            // The whole reason this class exists: lexicographically "1.10.0" < "1.9.0", which is wrong and
            // only bites once a plugin reaches its tenth minor release.
            assertTrue(SemanticVersion.isNewer("1.10.0", "1.9.0"));
            assertTrue(SemanticVersion.isNewer("2.0.0", "1.99.99"));
            assertTrue(SemanticVersion.isNewer("1.0.1", "1.0.0"));
            assertFalse(SemanticVersion.isNewer("1.0.0", "1.0.0"));
        }

        @Test
        @DisplayName("a release outranks its own pre-release")
        void releaseBeatsPreRelease() {
            assertTrue(SemanticVersion.isNewer("1.2.0", "1.2.0-rc.1"));
            assertFalse(SemanticVersion.isNewer("1.2.0-rc.1", "1.2.0"));
        }

        @Test
        @DisplayName("pre-releases order by identifier, numerically where both are numeric")
        void preReleasesOrderByIdentifier() {
            assertTrue(SemanticVersion.isNewer("1.0.0-rc.10", "1.0.0-rc.2"));
            assertTrue(SemanticVersion.isNewer("1.0.0-beta", "1.0.0-alpha"));
            // Numeric identifiers rank below alphanumeric ones, per semver.org.
            assertTrue(SemanticVersion.isNewer("1.0.0-alpha", "1.0.0-1"));
            assertTrue(SemanticVersion.isNewer("1.0.0-rc.1.1", "1.0.0-rc.1"));
        }

        @Test
        @DisplayName("build metadata does not affect precedence")
        void buildIsIgnored() {
            assertEquals(0, SemanticVersion.compare("1.0.0+a", "1.0.0+b"));
            assertEquals(SemanticVersion.parse("1.0.0+a"), SemanticVersion.parse("1.0.0+b"));
        }

        @Test
        @DisplayName("an unparseable version never looks newest")
        void unparseableSortsFirst() {
            // A plugin whose version nobody can read must not win a latest-version race.
            assertFalse(SemanticVersion.isNewer("garbage", "1.0.0"));
            assertTrue(SemanticVersion.isNewer("1.0.0", "garbage"));
        }

        @Test
        @DisplayName("sorts a release history the way a human would read it")
        void sortsAHistory() {
            List<SemanticVersion> versions = new ArrayList<>(List.of(
                    SemanticVersion.parse("1.2.0"),
                    SemanticVersion.parse("2.0.0-rc.1"),
                    SemanticVersion.parse("1.0.0"),
                    SemanticVersion.parse("1.10.0"),
                    SemanticVersion.parse("2.0.0"),
                    SemanticVersion.parse("1.9.0")));

            versions.sort(SemanticVersion.ASCENDING);

            assertEquals(List.of("1.0.0", "1.2.0", "1.9.0", "1.10.0", "2.0.0-rc.1", "2.0.0"),
                    versions.stream().map(SemanticVersion::toString).toList());
        }
    }

    @Nested
    @DisplayName("Breaking changes")
    class BreakingChanges {

        @Test
        @DisplayName("a major bump is breaking, a minor or patch bump is not")
        void majorIsBreaking() {
            assertTrue(SemanticVersion.parse("1.0.0").isBreakingChangeTo(SemanticVersion.parse("2.0.0")));
            assertFalse(SemanticVersion.parse("1.0.0").isBreakingChangeTo(SemanticVersion.parse("1.2.0")));
            assertFalse(SemanticVersion.parse("1.2.0").isBreakingChangeTo(SemanticVersion.parse("1.2.1")));
        }

        @Test
        @DisplayName("below 1.0.0 a minor bump is breaking, because semver promises nothing there")
        void zeroMajorIsUnstable() {
            assertTrue(SemanticVersion.parse("0.1.0").isBreakingChangeTo(SemanticVersion.parse("0.2.0")));
            assertFalse(SemanticVersion.parse("0.1.0").isBreakingChangeTo(SemanticVersion.parse("0.1.5")));
        }
    }
}
