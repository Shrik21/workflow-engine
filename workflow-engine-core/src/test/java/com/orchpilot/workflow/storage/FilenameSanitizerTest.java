package com.orchpilot.workflow.storage;

import com.orchpilot.workflow.storage.util.FilenameSanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Filename sanitising, weighted towards the attacks it exists to stop.
 *
 * <p>The assertions are deliberately about the <em>property</em> that must hold — no separator, no traversal,
 * never blank — rather than about one exact output string. A future change that produces a different but equally
 * safe name should not fail these; a change that lets a separator through must.
 */
class FilenameSanitizerTest {

    // ------------------------------------------------------------------ traversal

    @ParameterizedTest
    @ValueSource(strings = {
            "../../etc/passwd",
            "..\\..\\windows\\system32\\config",
            "../secret.txt",
            "..%2F..%2Fsecret.txt",          // percent-encoded
            "..%252f..%252fsecret.txt",      // double-encoded
            "....//....//secret.txt",        // survives a naive "remove ../" filter
            "/etc/shadow",
            "C:\\Windows\\evil.exe",
            "\\\\attacker\\share\\payload.dll",
            "C:file.txt",                     // drive-relative, no separator at all
    })
    @DisplayName("no traversal or absolute path survives sanitising")
    void refusesTraversal(String hostile) {
        String safe = FilenameSanitizer.sanitize(hostile);

        assertThat(safe)
                .doesNotContain("/")
                .doesNotContain("\\")
                .doesNotContain("..")
                .doesNotContain(":")
                .isNotBlank();
    }

    @Test
    @DisplayName("the documented example reduces to its basename")
    void reducesToBasename() {
        // The behaviour the specification calls for: ../../secret.txt must become something safe such as
        // secret.txt.
        assertThat(FilenameSanitizer.sanitize("../../secret.txt")).isEqualTo("secret.txt");
    }

    // ------------------------------------------------------------------ Windows quirks

    @ParameterizedTest
    @ValueSource(strings = {"CON", "con.txt", "PRN", "AUX", "NUL", "COM1", "LPT1.log"})
    @DisplayName("Windows device names are defused")
    void defusesDeviceNames(String device) {
        String safe = FilenameSanitizer.sanitize(device);

        // Opening "CON" opens the console rather than creating a file, so the name must not survive as-is.
        assertThat(safe).isNotEqualToIgnoringCase(device).startsWith("_");
    }

    @Test
    @DisplayName("trailing dots and spaces are removed, because Windows removes them silently")
    void stripsTrailingDotsAndSpaces() {
        // Without this, "report.txt." and "report.txt" are different strings but the same file on Windows.
        assertThat(FilenameSanitizer.sanitize("report.txt.")).isEqualTo("report.txt");
        assertThat(FilenameSanitizer.sanitize("report.txt   ")).isEqualTo("report.txt");
        assertThat(FilenameSanitizer.sanitize("report.txt. . .")).isEqualTo("report.txt");
    }

    @Test
    @DisplayName("a leading dot is removed so the file is not hidden")
    void stripsLeadingDots() {
        assertThat(FilenameSanitizer.sanitize(".bashrc")).isEqualTo("bashrc");
        assertThat(FilenameSanitizer.sanitize("..")).isEqualTo(FilenameSanitizer.FALLBACK);
        assertThat(FilenameSanitizer.sanitize(".")).isEqualTo(FilenameSanitizer.FALLBACK);
    }

    // ------------------------------------------------------------------ control characters and blanks

    @Test
    @DisplayName("a NUL byte cannot reach the filesystem")
    void removesNulBytes() {
        String safe = FilenameSanitizer.sanitize("invoice.pdf\u0000.exe");

        assertThat(safe).doesNotContain("\u0000");
    }

    @Test
    @DisplayName("newlines and quotes are removed, because the name is echoed in a response header")
    void removesHeaderBreakingCharacters() {
        String safe = FilenameSanitizer.sanitize("a\r\nSet-Cookie: x=1\"'.pdf");

        assertThat(safe).doesNotContain("\r").doesNotContain("\n").doesNotContain("\"");
    }

    @Test
    @DisplayName("null, blank and unusable names fall back rather than producing an empty path segment")
    void fallsBackWhenNothingSurvives() {
        assertThat(FilenameSanitizer.sanitize(null)).isEqualTo(FilenameSanitizer.FALLBACK);
        assertThat(FilenameSanitizer.sanitize("")).isEqualTo(FilenameSanitizer.FALLBACK);
        assertThat(FilenameSanitizer.sanitize("   ")).isEqualTo(FilenameSanitizer.FALLBACK);
        assertThat(FilenameSanitizer.sanitize("/")).isEqualTo(FilenameSanitizer.FALLBACK);
    }

    // ------------------------------------------------------------------ ordinary names

    @Test
    @DisplayName("a normal filename passes through unchanged")
    void keepsOrdinaryNames() {
        assertThat(FilenameSanitizer.sanitize("customer-data.xlsx")).isEqualTo("customer-data.xlsx");
        assertThat(FilenameSanitizer.sanitize("Invoice 2024 Q3.pdf")).isEqualTo("Invoice 2024 Q3.pdf");
        assertThat(FilenameSanitizer.sanitize("report_v2.final.docx")).isEqualTo("report_v2.final.docx");
    }

    @Test
    @DisplayName("a very long name is shortened but keeps its extension")
    void truncatesKeepingExtension() {
        String safe = FilenameSanitizer.sanitize("a".repeat(400) + ".pdf");

        assertThat(safe).hasSizeLessThanOrEqualTo(120).endsWith(".pdf");
    }

    // ------------------------------------------------------------------ path segments

    @Test
    @DisplayName("a path segment that could escape is rejected rather than cleaned")
    void rejectsUnsafeSegments() {
        // These are platform-generated values. Quietly rewriting one would hide a far deeper problem than a
        // bad upload, so the contract is to throw.
        assertThatThrownBy(() -> FilenameSanitizer.requireSafeSegment("..", "workflowId"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilenameSanitizer.requireSafeSegment("a/b", "workflowId"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilenameSanitizer.requireSafeSegment("a\\b", "workflowId"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilenameSanitizer.requireSafeSegment("", "workflowId"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilenameSanitizer.requireSafeSegment(null, "workflowId"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a real workflow id is accepted")
    void acceptsRealIdentifiers() {
        assertThat(FilenameSanitizer.requireSafeSegment("65abc123def456", "workflowId"))
                .isEqualTo("65abc123def456");
        assertThat(FilenameSanitizer.requireSafeSegment("WF-100", "workflowId")).isEqualTo("WF-100");
    }
}
