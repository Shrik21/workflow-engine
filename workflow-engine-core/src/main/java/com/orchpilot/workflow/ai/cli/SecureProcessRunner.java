package com.orchpilot.workflow.ai.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external program, and nothing else.
 *
 * <h2>The rules this enforces</h2>
 *
 * <ul>
 *   <li><b>No shell, ever.</b> {@link ProcessBuilder} is given a pre-split argument list, so the operating
 *       system executes exactly that program with exactly those arguments. There is no string for a shell to
 *       reinterpret, which is why {@code Runtime.exec(userInput)} — and any variant that concatenates a command
 *       line — is not used and must not be introduced.</li>
 *   <li><b>Arguments are supplied by the engine, not the user.</b> Callers pass a structured argument list
 *       built in code. A user-supplied prompt is passed on <em>stdin</em>, never as an argument, so nothing a
 *       user types can become a flag.</li>
 *   <li><b>Arguments are still checked.</b> Even engine-built arguments are rejected if they contain a null
 *       byte or a line break, because those are the two characters that can split one argument into two in
 *       some downstream consumer.</li>
 *   <li><b>Bounded output.</b> Both streams are drained concurrently and capped. Draining concurrently is not
 *       an optimisation: a process that fills its stderr pipe while the parent reads only stdout deadlocks
 *       until the timeout.</li>
 *   <li><b>Bounded time.</b> The process is destroyed on timeout, forcibly if it ignores the first request.</li>
 *   <li><b>A minimal environment.</b> The child inherits only what it needs, so the engine's own configuration
 *       — including anything sensitive that reached its environment — is not handed to a third-party binary.</li>
 * </ul>
 */
@Component
public class SecureProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(SecureProcessRunner.class);

    /**
     * Environment variables passed through to the child.
     *
     * <p>An allowlist rather than the inherited environment. A CLI needs enough to find the user profile it
     * stores its own login in, and to resolve libraries; it does not need the engine's database URI, its JWT
     * signing key, or any cloud credential that happens to be in the engine's environment.
     */
    private static final List<String> ENVIRONMENT_ALLOWLIST = List.of(
            // POSIX
            "HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "PATH", "SHELL", "TMPDIR", "XDG_CONFIG_HOME",
            "XDG_CACHE_HOME",
            // Windows
            "USERPROFILE", "APPDATA", "LOCALAPPDATA", "HOMEDRIVE", "HOMEPATH", "SystemRoot", "windir",
            "TEMP", "TMP", "PATHEXT", "COMSPEC", "ProgramFiles", "ProgramData", "NUMBER_OF_PROCESSORS");

    private final AiCliProperties properties;

    public SecureProcessRunner(AiCliProperties properties) {
        this.properties = properties;
    }

    /**
     * Runs a program and waits for it.
     *
     * @param executable      validated path to the program
     * @param arguments       engine-built arguments; never assembled from user text
     * @param stdin           text to write to the process's standard input, or null for none
     * @param timeoutSeconds  how long to wait before destroying it
     * @return what it produced
     * @throws AiCliException when it cannot be started, is interrupted, or exceeds the timeout
     */
    public ProcessResult run(Path executable, List<String> arguments, String stdin, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        for (String argument : arguments) {
            command.add(requireCleanArgument(argument));
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        // Explicitly not redirectErrorStream(true): stderr is the channel a CLI uses for diagnostics, and
        // merging it into stdout would corrupt JSON output that a caller needs to parse.
        builder.redirectErrorStream(false);
        applyEnvironment(builder);

        long startedAt = System.currentTimeMillis();
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            throw new AiCliException("AI_CLI_START_FAILED",
                    "Could not start '" + executable + "': " + ex.getMessage());
        }

        try {
            // Both streams drained on their own threads before waiting, or a full pipe deadlocks the child.
            CompletableFuture<byte[]> out = drain(process.getInputStream());
            CompletableFuture<byte[]> err = drain(process.getErrorStream());
            writeStdin(process, stdin);

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                destroy(process);
                throw new AiCliException("AI_CLI_TIMEOUT",
                        "'" + executable.getFileName() + "' did not finish within " + timeoutSeconds
                                + " seconds and was stopped.", true);
            }
            byte[] stdoutBytes = out.join();
            byte[] stderrBytes = err.join();
            int cap = properties.getMaxOutputBytes();
            boolean truncated = stdoutBytes.length >= cap || stderrBytes.length >= cap;

            return new ProcessResult(process.exitValue(),
                    new String(stdoutBytes, StandardCharsets.UTF_8),
                    new String(stderrBytes, StandardCharsets.UTF_8),
                    truncated,
                    System.currentTimeMillis() - startedAt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            destroy(process);
            throw new AiCliException("AI_CLI_INTERRUPTED",
                    "Waiting for '" + executable.getFileName() + "' was interrupted.", true);
        } finally {
            if (process.isAlive()) {
                destroy(process);
            }
        }
    }

    /**
     * Rejects an argument that could be split into two by something downstream.
     *
     * <p>Arguments are engine-built, so this should never fire — which is exactly why it is worth having: if it
     * ever does, a caller has started assembling arguments from input and this is where that is caught.
     */
    private static String requireCleanArgument(String argument) {
        if (argument == null) {
            throw new AiCliException("AI_CLI_INVALID_ARGUMENT", "A null argument cannot be passed to a CLI.");
        }
        if (argument.indexOf('\0') >= 0 || argument.indexOf('\n') >= 0 || argument.indexOf('\r') >= 0) {
            throw new AiCliException("AI_CLI_INVALID_ARGUMENT",
                    "An argument contains a line break or null byte and was refused. Prompt text belongs on "
                            + "standard input, not in an argument.");
        }
        return argument;
    }

    private static void applyEnvironment(ProcessBuilder builder) {
        var environment = builder.environment();
        var inherited = new java.util.LinkedHashMap<>(environment);
        environment.clear();
        for (var entry : inherited.entrySet()) {
            for (String allowed : ENVIRONMENT_ALLOWLIST) {
                // Windows environment variable names are case-insensitive.
                if (allowed.equalsIgnoreCase(entry.getKey())) {
                    environment.put(entry.getKey(), entry.getValue());
                    break;
                }
            }
        }
    }

    /** Writes the prompt to the child and closes the stream, so a CLI reading to EOF proceeds. */
    private static void writeStdin(Process process, String stdin) {
        try (OutputStream input = process.getOutputStream()) {
            if (stdin != null && !stdin.isEmpty()) {
                input.write(stdin.getBytes(StandardCharsets.UTF_8));
                input.flush();
            }
        } catch (IOException ex) {
            // A process that exited before reading its input is a normal race, not a failure of its own; the
            // exit code and streams tell the real story.
            log.debug("Could not write to the CLI's standard input: {}", ex.getMessage());
        }
    }

    private CompletableFuture<byte[]> drain(InputStream stream) {
        int cap = properties.getMaxOutputBytes();
        return CompletableFuture.supplyAsync(() -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            try (stream) {
                int read;
                while ((read = stream.read(chunk)) >= 0) {
                    int remaining = cap - buffer.size();
                    if (remaining <= 0) {
                        // Keep draining so the child never blocks on a full pipe, but stop retaining.
                        continue;
                    }
                    buffer.write(chunk, 0, Math.min(read, remaining));
                }
            } catch (IOException ex) {
                log.debug("Reading a CLI output stream ended early: {}", ex.getMessage());
            }
            return buffer.toByteArray();
        });
    }

    private static void destroy(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
