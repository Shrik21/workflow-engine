package com.orchpilot.workflow.ai.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds an AI CLI on the machine the engine is running on.
 *
 * <h2>PATH first, then likely directories</h2>
 *
 * The specification is explicit that no installation directory may be assumed, and it is right to be: npm's
 * global prefix moves, distributions disagree about {@code /usr/bin} versus {@code /usr/local/bin}, and a
 * per-user install lands under the home directory. So detection walks the engine user's {@code PATH} first —
 * which is the answer whenever the CLI was installed normally — and only then tries a list of common locations
 * as a fallback, reporting every candidate it finds rather than picking one silently.
 *
 * <p>Nothing here executes anything. Detection reports paths; confirming that a path works is
 * {@link AiCliExecutionService#testConnection}, which is a separate permission.
 */
@Component
public class CliDetector {

    private static final Logger log = LoggerFactory.getLogger(CliDetector.class);

    /**
     * One discovered candidate.
     *
     * @param path   absolute path to the executable
     * @param source how it was found, so the UI can say "on PATH" rather than presenting a bare list
     */
    public record Candidate(String path, String source) {
    }

    /**
     * Searches for a CLI by name.
     *
     * @param command the base name, e.g. {@code claude}
     * @return every candidate found, PATH entries first; empty when nothing is installed
     */
    public List<Candidate> detect(String command) {
        OperatingSystemType host = OperatingSystemType.detectHost();
        Set<String> seen = new LinkedHashSet<>();
        List<Candidate> found = new ArrayList<>();

        for (Path path : searchPath(command, host)) {
            if (seen.add(key(path))) {
                found.add(new Candidate(path.toString(), "PATH"));
            }
        }
        for (Path path : commonLocations(command, host)) {
            if (seen.add(key(path))) {
                found.add(new Candidate(path.toString(), "common install location"));
            }
        }
        return found;
    }

    /**
     * Walks {@code PATH} the way the operating system would.
     *
     * <p>This is the {@code which claude} the specification asks for, done in-process rather than by running
     * {@code which} — spawning a shell utility to find out where a program is would be a second process, on a
     * code path whose whole purpose is to be careful about spawning processes.
     */
    private List<Path> searchPath(String command, OperatingSystemType host) {
        List<Path> found = new ArrayList<>();
        String pathVariable = System.getenv("PATH");
        if (pathVariable == null || pathVariable.isBlank()) {
            return found;
        }
        List<String> names = candidateNames(command, host);
        String separator = host == OperatingSystemType.WINDOWS ? ";" : ":";

        for (String directory : pathVariable.split(java.util.regex.Pattern.quote(separator))) {
            if (directory == null || directory.isBlank()) {
                continue;
            }
            for (String name : names) {
                try {
                    Path candidate = Paths.get(directory.trim(), name);
                    if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                        found.add(candidate.toAbsolutePath().normalize());
                    }
                } catch (InvalidPathException ex) {
                    log.debug("Skipping unusable PATH entry '{}': {}", directory, ex.getMessage());
                }
            }
        }
        return found;
    }

    /**
     * Directories a CLI commonly lands in, as a fallback when it is not on the engine's PATH.
     *
     * <p>A fallback, deliberately — a service's PATH is often narrower than a login shell's, so a CLI that
     * {@code which} would find in a terminal may be invisible to the engine. These are candidates to offer,
     * never an assumption about where the program must be.
     */
    private List<Path> commonLocations(String command, OperatingSystemType host) {
        List<Path> roots = new ArrayList<>();
        String home = System.getProperty("user.home");

        if (host == OperatingSystemType.WINDOWS) {
            addIfSet(roots, System.getenv("APPDATA"), "npm");
            addIfSet(roots, System.getenv("LOCALAPPDATA"), "npm");
            addIfSet(roots, System.getenv("LOCALAPPDATA"), "Programs", "claude");
            addIfSet(roots, System.getenv("ProgramFiles"), "nodejs");
            if (home != null) {
                addIfSet(roots, home, "AppData", "Roaming", "npm");
                addIfSet(roots, home, ".local", "bin");
            }
        } else {
            roots.add(Paths.get("/usr/local/bin"));
            roots.add(Paths.get("/usr/bin"));
            roots.add(Paths.get("/bin"));
            roots.add(Paths.get("/opt/homebrew/bin"));
            roots.add(Paths.get("/snap/bin"));
            if (home != null) {
                addIfSet(roots, home, ".local", "bin");
                addIfSet(roots, home, ".npm-global", "bin");
                addIfSet(roots, home, "node_modules", ".bin");
                addIfSet(roots, home, ".bun", "bin");
            }
        }

        List<String> names = candidateNames(command, host);
        List<Path> found = new ArrayList<>();
        for (Path root : roots) {
            for (String name : names) {
                Path candidate = root.resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    found.add(candidate.toAbsolutePath().normalize());
                }
            }
        }
        return found;
    }

    /** The file names one command can have on this OS: bare on POSIX, extension-bearing on Windows. */
    private static List<String> candidateNames(String command, OperatingSystemType host) {
        if (host != OperatingSystemType.WINDOWS) {
            return List.of(command);
        }
        // .cmd first: an npm-installed CLI is a .cmd shim, which is the overwhelmingly common case on Windows.
        return List.of(command + ".cmd", command + ".exe", command + ".bat", command);
    }

    private static void addIfSet(List<Path> roots, String base, String... segments) {
        if (base == null || base.isBlank()) {
            return;
        }
        try {
            Path path = Paths.get(base);
            for (String segment : segments) {
                path = path.resolve(segment);
            }
            roots.add(path);
        } catch (InvalidPathException ex) {
            log.debug("Skipping unusable candidate directory '{}': {}", base, ex.getMessage());
        }
    }

    /** Case-insensitive on Windows, so the same file found twice is not offered twice. */
    private static String key(Path path) {
        String text = path.toString();
        return OperatingSystemType.detectHost() == OperatingSystemType.WINDOWS
                ? text.toLowerCase(java.util.Locale.ROOT) : text;
    }
}
