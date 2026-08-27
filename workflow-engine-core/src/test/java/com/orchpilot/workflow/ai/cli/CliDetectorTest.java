package com.orchpilot.workflow.ai.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Auto-detection.
 *
 * <p>Whether a CLI is installed on the machine running these tests is unknowable, so the assertions are about
 * the properties detection must hold regardless: it never throws, never invents a path, never returns a
 * duplicate, and finds a program that genuinely is on PATH.
 */
class CliDetectorTest {

    private final CliDetector detector = new CliDetector();

    @Test
    @DisplayName("returns an empty list rather than failing when nothing is installed")
    void quietWhenNothingFound() {
        List<CliDetector.Candidate> found = detector.detect("definitely-not-installed-xyz");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("finds a program that is genuinely on PATH")
    void findsSomethingOnPath() {
        // java is on PATH wherever Maven ran these tests, so it is a dependable stand-in for a CLI.
        List<CliDetector.Candidate> found = detector.detect("java");

        assertThat(found).isNotEmpty();
        assertThat(found).anyMatch(c -> "PATH".equals(c.source()));
    }

    @Test
    @DisplayName("every candidate is an absolute path that exists")
    void candidatesAreReal() {
        for (CliDetector.Candidate candidate : detector.detect("java")) {
            java.nio.file.Path path = java.nio.file.Paths.get(candidate.path());
            assertThat(path.isAbsolute()).isTrue();
            assertThat(java.nio.file.Files.isRegularFile(path)).isTrue();
        }
    }

    @Test
    @DisplayName("does not offer the same file twice")
    void deduplicates() {
        List<CliDetector.Candidate> found = detector.detect("java");

        // PATH and the common-locations fallback overlap on most machines.
        assertThat(found).extracting(CliDetector.Candidate::path).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("says how each candidate was found")
    void reportsSource() {
        for (CliDetector.Candidate candidate : detector.detect("java")) {
            assertThat(candidate.source()).isIn("PATH", "common install location");
        }
    }

    @Test
    @DisplayName("searches for the real CLI without incident")
    void searchesForClaude() {
        // Whether it is installed here is not the point; that detection completes safely is.
        assertThatCode(() -> detector.detect("claude")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("detects the host operating system")
    void detectsHost() {
        OperatingSystemType host = OperatingSystemType.detectHost();

        assertThat(host).isNotNull();
        assertThat(host.isPosix()).isEqualTo(host != OperatingSystemType.WINDOWS);
    }
}
