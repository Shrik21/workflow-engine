package com.orchpilot.workflow.ai.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Path validation — the control that decides what this engine is willing to execute.
 *
 * <p>Weighted towards refusal: a wrongly accepted path is the one failure here with real consequences.
 */
class ExecutablePathValidatorTest {

    private AiCliProperties properties;
    private ExecutablePathValidator validator;

    @BeforeEach
    void setUp() {
        properties = new AiCliProperties();
        validator = new ExecutablePathValidator(properties);
    }

    // ------------------------------------------------------------------ accepted

    @ParameterizedTest
    @ValueSource(strings = {
            "C:\\Users\\dev\\AppData\\Roaming\\npm\\claude.cmd",
            "C:\\Program Files\\claude\\claude.exe",
            "D:/tools/claude.cmd"})
    @DisplayName("accepts well-formed Windows paths")
    void acceptsWindowsPaths(String path) {
        assertThatCode(() -> validator.validateShape(path, OperatingSystemType.WINDOWS))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/usr/local/bin/claude",
            "/usr/bin/claude",
            "/home/dev/.local/bin/claude",
            "/snap/bin/claude"})
    @DisplayName("accepts well-formed Linux paths")
    void acceptsLinuxPaths(String path) {
        assertThatCode(() -> validator.validateShape(path, OperatingSystemType.UBUNTU))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validates a Windows path from a Linux engine, and the reverse")
    void validatesCrossPlatform() {
        // A configuration may legitimately be prepared for a host this engine is not running on, so shape
        // validation must not depend on the host's own path semantics.
        assertThatCode(() -> validator.validateShape(
                "C:\\Users\\dev\\AppData\\Roaming\\npm\\claude.cmd", OperatingSystemType.WINDOWS))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateShape("/usr/local/bin/claude", OperatingSystemType.LINUX))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ refused

    @ParameterizedTest
    @ValueSource(strings = {
            "/usr/local/bin/claude && rm -rf /",
            "/usr/local/bin/claude; cat /etc/passwd",
            "/usr/local/bin/claude | tee /tmp/x",
            "/usr/local/bin/claude`whoami`",
            "/usr/local/bin/claude$(id)",
            "/usr/local/bin/claude > /tmp/out",
            "/usr/local/bin/cl*ude"})
    @DisplayName("refuses anything carrying shell syntax")
    void refusesShellSyntax(String path) {
        assertThatThrownBy(() -> validator.validateShape(path, OperatingSystemType.LINUX))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode()).isEqualTo("AI_CLI_PATH_INVALID"));
    }

    @Test
    @DisplayName("refuses a newline, which could split one argument into two")
    void refusesNewline() {
        assertThatThrownBy(() -> validator.validateShape("/usr/bin/claude\n--dangerous",
                OperatingSystemType.LINUX))
                .isInstanceOf(AiCliException.class)
                .hasMessageContaining("line break");
    }

    @Test
    @DisplayName("refuses traversal, which would defeat the allowed-directory check")
    void refusesTraversal() {
        assertThatThrownBy(() -> validator.validateShape("/usr/local/bin/../../tmp/claude",
                OperatingSystemType.LINUX))
                .isInstanceOf(AiCliException.class)
                .hasMessageContaining("..");
    }

    @Test
    @DisplayName("refuses a UNC path, because the share's owner would choose what runs")
    void refusesUnc() {
        assertThatThrownBy(() -> validator.validateShape("\\\\fileserver\\tools\\claude.exe",
                OperatingSystemType.WINDOWS))
                .isInstanceOf(AiCliException.class)
                .hasMessageContaining("Network paths");
    }

    @Test
    @DisplayName("refuses a relative path")
    void refusesRelative() {
        assertThatThrownBy(() -> validator.validateShape("claude", OperatingSystemType.LINUX))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_CLI_PATH_NOT_ABSOLUTE"));
        assertThatThrownBy(() -> validator.validateShape("npm\\claude.cmd", OperatingSystemType.WINDOWS))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_CLI_PATH_NOT_ABSOLUTE"));
    }

    @Test
    @DisplayName("refuses a program that is not an AI CLI")
    void refusesUnknownProgram() {
        // The whole point of the feature is running an AI CLI; a path to anything else is a different feature.
        assertThatThrownBy(() -> validator.validateShape("/bin/bash", OperatingSystemType.LINUX))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_CLI_PATH_NOT_ALLOWED"));
        assertThatThrownBy(() -> validator.validateShape("C:\\Windows\\System32\\cmd.exe",
                OperatingSystemType.WINDOWS))
                .isInstanceOf(AiCliException.class)
                .hasMessageContaining("not an AI CLI");
    }

    @Test
    @DisplayName("refuses a Windows path without a runnable extension")
    void refusesBadWindowsExtension() {
        assertThatThrownBy(() -> validator.validateShape("C:\\tools\\claude.txt",
                OperatingSystemType.WINDOWS))
                .isInstanceOf(AiCliException.class)
                .hasMessageContaining(".cmd");
    }

    @Test
    @DisplayName("refuses a Unix path configured as Windows, and the reverse")
    void refusesMismatchedOs() {
        assertThatThrownBy(() -> validator.validateShape("/usr/bin/claude", OperatingSystemType.WINDOWS))
                .isInstanceOf(AiCliException.class);
        assertThatThrownBy(() -> validator.validateShape("C:\\tools\\claude.cmd", OperatingSystemType.LINUX))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isIn("AI_CLI_PATH_WRONG_OS", "AI_CLI_PATH_NOT_ABSOLUTE"));
    }

    @Test
    @DisplayName("refuses a blank path")
    void refusesBlank() {
        assertThatThrownBy(() -> validator.validateShape("  ", OperatingSystemType.LINUX))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_CLI_PATH_REQUIRED"));
    }

    // ------------------------------------------------------------------ allowed directories

    @Test
    @DisplayName("an allowlist confines the path, and an empty one does not")
    void allowedDirectories() {
        assertThatCode(() -> validator.validateShape("/opt/ai/claude", OperatingSystemType.LINUX))
                .doesNotThrowAnyException();

        properties.setAllowedDirectories(List.of("/usr/local/bin"));

        assertThatCode(() -> validator.validateShape("/usr/local/bin/claude", OperatingSystemType.LINUX))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateShape("/opt/ai/claude", OperatingSystemType.LINUX))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_CLI_PATH_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("a prefix that is not a directory boundary does not satisfy the allowlist")
    void allowlistRespectsDirectoryBoundary() {
        properties.setAllowedDirectories(List.of("/usr/local/bin"));

        // /usr/local/bin-evil starts with the allowed string but is a different directory.
        assertThatThrownBy(() -> validator.validateShape("/usr/local/bin-evil/claude",
                OperatingSystemType.LINUX))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_CLI_PATH_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("a malformed allowlist entry does not open the gate")
    void malformedAllowlistEntryStillRefuses() {
        properties.setAllowedDirectories(List.of("", "   "));

        assertThatThrownBy(() -> validator.validateShape("/opt/ai/claude", OperatingSystemType.LINUX))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_CLI_PATH_NOT_ALLOWED"));
    }

    // ------------------------------------------------------------------ execution-time checks

    @Test
    @DisplayName("a path that passes shape validation still fails when nothing is there")
    void missingFileIsRefusedAtExecutionTime(@TempDir Path dir) {
        OperatingSystemType host = OperatingSystemType.detectHost();
        String missing = dir.resolve(host == OperatingSystemType.WINDOWS ? "claude.cmd" : "claude").toString();

        assertThatThrownBy(() -> validator.validateForExecution(missing, host))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode()).isEqualTo("AI_CLI_NOT_FOUND"))
                // The container case is the one people lose the most time to.
                .hasMessageContaining("container");
    }

    @Test
    @DisplayName("a configuration for another OS cannot be executed here")
    void refusesToExecuteForeignOs() {
        OperatingSystemType foreign = OperatingSystemType.detectHost() == OperatingSystemType.WINDOWS
                ? OperatingSystemType.LINUX : OperatingSystemType.WINDOWS;
        String path = foreign == OperatingSystemType.WINDOWS
                ? "C:\\tools\\claude.cmd" : "/usr/local/bin/claude";

        assertThatThrownBy(() -> validator.validateForExecution(path, foreign))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode()).isEqualTo("AI_CLI_OS_MISMATCH"));
    }

    @Test
    @DisplayName("a directory is not a program")
    void refusesDirectory(@TempDir Path dir) throws IOException {
        OperatingSystemType host = OperatingSystemType.detectHost();
        Path asDirectory = dir.resolve(host == OperatingSystemType.WINDOWS ? "claude.cmd" : "claude");
        Files.createDirectory(asDirectory);

        assertThatThrownBy(() -> validator.validateForExecution(asDirectory.toString(), host))
                .isInstanceOf(AiCliException.class)
                .hasMessageContaining("not a program");
    }

    @Test
    @DisplayName("re-validates at execution time rather than trusting the stored path")
    void revalidatesAtExecutionTime(@TempDir Path dir) throws IOException {
        OperatingSystemType host = OperatingSystemType.detectHost();
        Path executable = dir.resolve(host == OperatingSystemType.WINDOWS ? "claude.cmd" : "claude");
        Files.createFile(executable);
        executable.toFile().setExecutable(true);

        assertThatCode(() -> validator.validateForExecution(executable.toString(), host))
                .doesNotThrowAnyException();

        // The operator narrows the allowlist after the configuration was saved; the next run must refuse.
        properties.setAllowedDirectories(List.of(host == OperatingSystemType.WINDOWS
                ? "C:\\approved" : "/approved"));

        assertThatThrownBy(() -> validator.validateForExecution(executable.toString(), host))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_CLI_PATH_NOT_ALLOWED"));
    }
}
