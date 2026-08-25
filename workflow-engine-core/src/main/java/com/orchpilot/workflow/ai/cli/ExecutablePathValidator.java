package com.orchpilot.workflow.ai.cli;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a configured string is a path this engine is willing to execute.
 *
 * <h2>What this is actually defending against</h2>
 *
 * The path arrives from an authenticated, permitted user, so this is not primarily an anti-attacker control —
 * it is what stops a configuration string from being reinterpreted as something other than a path to a program.
 * Each rule corresponds to a way that could happen:
 *
 * <ul>
 *   <li><b>No shell metacharacters.</b> The runner never invokes a shell, so {@code claude && rm -rf /} would
 *       today be treated as one absurd filename and fail. That is correct but fragile — it depends on a
 *       property of the runner staying true forever. Rejecting the characters here means the safety does not
 *       rest on that alone.</li>
 *   <li><b>Absolute paths only.</b> A relative path resolves against the engine's working directory, which is
 *       not something the person configuring it can see, and would change meaning if the engine were restarted
 *       elsewhere.</li>
 *   <li><b>No traversal segments.</b> A stored {@code ..} makes the allowed-directory check meaningless.</li>
 *   <li><b>No UNC paths.</b> {@code \\server\share\claude.exe} executes code fetched from a network share, so
 *       whoever controls that share controls what the engine runs.</li>
 *   <li><b>Extension allowlist on Windows.</b> {@code .cmd}, {@code .exe}, {@code .bat}, {@code .ps1}.</li>
 *   <li><b>Known program names.</b> Installation directories differ per machine, so the allowlist is on the
 *       program's name rather than its location — the directory may be a surprise, the program may not.</li>
 * </ul>
 *
 * <h2>Shape is checked as a string, not as a {@link Path}</h2>
 *
 * A configuration may target a different OS than the engine currently runs on — an operator preparing an Ubuntu
 * configuration from a Windows workstation, or the reverse. {@code Paths.get} applies the <em>host's</em> rules,
 * so a Windows path parsed on a Linux JVM comes back as a relative single-segment name and a POSIX path parsed
 * on Windows loses its meaning. Shape is therefore validated against the target OS's rules directly, and
 * {@link Path} is used only in {@link #validateForExecution}, where host and target are known to agree.
 *
 * <p>Validation runs on every write <em>and</em> again immediately before every execution. Checking once at
 * write time would be a time-of-check/time-of-use gap: the allowlist could be narrowed, or the file replaced,
 * between configuring and running.
 */
@Component
public class ExecutablePathValidator {

    /** Characters that mean something to a shell. Defence in depth, not the primary control. */
    private static final Set<Character> SHELL_METACHARACTERS = Set.of(
            '&', '|', ';', '<', '>', '`', '$', '\n', '\r', '\0', '*', '?', '{', '}', '(', ')', '!', '"', '\'');

    private static final Set<String> WINDOWS_EXTENSIONS = Set.of(".cmd", ".exe", ".bat", ".ps1");

    /** e.g. {@code C:\} or {@code d:/} — a drive letter, a colon, a separator. */
    private static final Pattern WINDOWS_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    /** Programs we are willing to run, so a configuration cannot point the engine at something unrelated. */
    private static final Set<String> KNOWN_NAMES = Set.of(
            "claude", "claude.cmd", "claude.exe", "claude.bat", "claude.ps1",
            "openai", "openai.cmd", "openai.exe",
            "gemini", "gemini.cmd", "gemini.exe",
            "ollama", "ollama.cmd", "ollama.exe");

    private final AiCliProperties properties;

    public ExecutablePathValidator(AiCliProperties properties) {
        this.properties = properties;
    }

    /**
     * Checks a configured path against the rules of the OS it targets, without touching the file system.
     *
     * @param rawPath         the configured string
     * @param operatingSystem the OS the configuration targets
     * @throws AiCliException when the string is not an acceptable executable path
     */
    public void validateShape(String rawPath, OperatingSystemType operatingSystem) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new AiCliException("AI_CLI_PATH_REQUIRED", "An executable path is required.");
        }
        String path = rawPath.trim();

        for (char c : path.toCharArray()) {
            if (SHELL_METACHARACTERS.contains(c)) {
                throw new AiCliException("AI_CLI_PATH_INVALID",
                        "The executable path contains " + describe(c) + ", which is not allowed. Give the path "
                                + "to the program only — arguments and shell syntax are never accepted here.");
            }
        }
        if (path.contains("..")) {
            throw new AiCliException("AI_CLI_PATH_INVALID",
                    "The executable path must not contain '..'. Give the fully resolved path.");
        }
        if (path.startsWith("\\\\") || path.startsWith("//")) {
            throw new AiCliException("AI_CLI_PATH_INVALID",
                    "Network paths are not allowed. Whoever controls that share would control what this engine "
                            + "executes — install the CLI locally instead.");
        }

        String fileName = fileName(path);

        if (operatingSystem == OperatingSystemType.WINDOWS) {
            if (!WINDOWS_ABSOLUTE.matcher(path).matches()) {
                throw new AiCliException("AI_CLI_PATH_NOT_ABSOLUTE",
                        "'" + path + "' is not an absolute Windows path. It should look like "
                                + "C:\\Users\\you\\AppData\\Roaming\\npm\\claude.cmd — use Detect "
                                + "Automatically to find it.");
            }
            String extension = extension(fileName);
            if (extension == null || !WINDOWS_EXTENSIONS.contains(extension)) {
                throw new AiCliException("AI_CLI_PATH_INVALID",
                        "On Windows the executable must end in .cmd, .exe, .bat or .ps1. '" + fileName
                                + "' does not.");
            }
        } else {
            if (!path.startsWith("/")) {
                throw new AiCliException("AI_CLI_PATH_NOT_ABSOLUTE",
                        "'" + path + "' is not an absolute " + operatingSystem + " path. It should start at "
                                + "the root, for example /usr/local/bin/claude.");
            }
            if (WINDOWS_ABSOLUTE.matcher(path).matches()) {
                throw new AiCliException("AI_CLI_PATH_WRONG_OS",
                        "'" + path + "' is a Windows path but this configuration targets " + operatingSystem
                                + ".");
            }
        }

        if (!KNOWN_NAMES.contains(fileName.toLowerCase(Locale.ROOT))) {
            throw new AiCliException("AI_CLI_PATH_NOT_ALLOWED",
                    "'" + fileName + "' is not an AI CLI this engine knows how to run. Expected one of: claude, "
                            + "claude.cmd, claude.exe (or the equivalent for another configured provider).");
        }

        requireInsideAllowedDirectory(path);
    }

    /**
     * Re-checks a path immediately before running it, including that the file is there and runnable.
     *
     * @param rawPath         the configured string
     * @param operatingSystem the OS the configuration targets
     * @return the resolved, normalised path to hand to the process builder
     * @throws AiCliException when the path is unacceptable or not runnable
     */
    public Path validateForExecution(String rawPath, OperatingSystemType operatingSystem) {
        validateShape(rawPath, operatingSystem);

        OperatingSystemType host = OperatingSystemType.detectHost();
        if (host.isPosix() != operatingSystem.isPosix()) {
            throw new AiCliException("AI_CLI_OS_MISMATCH",
                    "This configuration targets " + operatingSystem + " but the engine is running on " + host
                            + ". A configuration can only be executed on a matching host.");
        }

        Path path;
        try {
            path = Paths.get(rawPath.trim()).normalize();
        } catch (InvalidPathException ex) {
            throw new AiCliException("AI_CLI_PATH_INVALID",
                    "'" + rawPath.trim() + "' is not a valid path on this system.");
        }
        if (!Files.exists(path)) {
            throw new AiCliException("AI_CLI_NOT_FOUND",
                    "No file at '" + path + "'. If OrchPilot runs in a container, the CLI must be installed "
                            + "inside that container — a path on the host machine is not reachable from it.");
        }
        if (Files.isDirectory(path)) {
            throw new AiCliException("AI_CLI_NOT_FOUND", "'" + path + "' is a directory, not a program.");
        }
        if (!Files.isExecutable(path)) {
            throw new AiCliException("AI_CLI_NOT_EXECUTABLE",
                    "'" + path + "' exists but is not executable by the user the engine runs as"
                            + (operatingSystem.isPosix() ? " — check chmod +x and the file's owner." : "."));
        }
        return path;
    }

    /** Refuses a path outside the operator's allowed directories; a blank allowlist permits any valid path. */
    private void requireInsideAllowedDirectory(String path) {
        List<String> allowed = properties.getAllowedDirectories();
        if (allowed.isEmpty()) {
            return;
        }
        String candidate = normaliseSeparators(path);
        for (String directory : allowed) {
            if (directory == null || directory.isBlank()) {
                continue;
            }
            String prefix = normaliseSeparators(directory.trim());
            if (!prefix.endsWith("/")) {
                prefix = prefix + "/";
            }
            // Compared as text, with separators unified, because the allowlist may describe a different OS
            // than the host — the same reason shape validation avoids Path.
            if (candidate.startsWith(prefix)) {
                return;
            }
        }
        throw new AiCliException("AI_CLI_PATH_NOT_ALLOWED",
                "'" + path + "' is outside the directories this engine permits AI CLI executables in. "
                        + "Ask an operator about workflow.engine.ai.cli.allowed-directories.");
    }

    private static String normaliseSeparators(String path) {
        String unified = path.replace('\\', '/');
        // Windows paths are case-insensitive; comparing C:/Users against c:/users must not fail.
        return OperatingSystemType.detectHost() == OperatingSystemType.WINDOWS
                ? unified.toLowerCase(Locale.ROOT) : unified;
    }

    /** The last segment, using whichever separator the target OS employs. */
    private static String fileName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? null : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String describe(char c) {
        return switch (c) {
            case '\n' -> "a line break";
            case '\r' -> "a carriage return";
            case '\0' -> "a null byte";
            default -> "'" + c + "'";
        };
    }
}
