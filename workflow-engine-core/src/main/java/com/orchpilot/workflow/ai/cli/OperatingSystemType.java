package com.orchpilot.workflow.ai.cli;

import java.util.Locale;

/**
 * The operating systems an AI CLI configuration can target.
 *
 * <p>This is not decoration: it decides how an executable path is validated, which extensions are acceptable,
 * and how detection searches. A Windows path (<code>C:\…\claude.cmd</code>) is nonsense on Ubuntu and a
 * <code>which</code> lookup is nonsense on Windows, so the configuration says which world it lives in rather
 * than the code guessing from the string.
 *
 * <p>{@link #UBUNTU} and {@link #LINUX} behave identically today and are kept apart because the specification
 * asks for both and because an operator reading a list of configurations wants to see which host is which.
 */
public enum OperatingSystemType {

    WINDOWS,
    UBUNTU,
    LINUX;

    /** @return whether this is a POSIX-like target, where paths are absolute from {@code /} and have no extension */
    public boolean isPosix() {
        return this != WINDOWS;
    }

    /**
     * Detects the operating system the engine is actually running on.
     *
     * <p>Used to warn when a configuration targets a different OS than the host — a configuration for Windows
     * on a Linux engine can never work, and saying so at configuration time is better than a confusing
     * "file not found" the first time someone runs it.
     *
     * @return the host's type; {@link #LINUX} for anything POSIX-like that is not recognisably Ubuntu
     */
    public static OperatingSystemType detectHost() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return WINDOWS;
        }
        // The JVM reports "Linux" for every distribution, so Ubuntu is not distinguishable here. LINUX is the
        // honest answer; an operator who wants the label UBUNTU sets it themselves.
        return LINUX;
    }
}
