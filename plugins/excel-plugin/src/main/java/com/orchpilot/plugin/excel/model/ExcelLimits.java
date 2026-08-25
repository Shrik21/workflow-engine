package com.orchpilot.plugin.excel.model;

import com.orchpilot.plugin.excel.exception.ExcelException;
import com.orchpilot.workflow.sdk.node.NodeConfiguration;

import java.time.Duration;

/**
 * The bounds one node attempt will not exceed.
 *
 * <h2>Why limits are mandatory rather than advisory</h2>
 *
 * A spreadsheet is attacker-influenced input running inside the engine's own process. A 20 MB xlsx can expand
 * to gigabytes of objects — the zip-bomb shape, and POI is a documented target for it — and a workbook with a
 * million rows will exhaust the heap of a process that is also running every other workflow. Neither is exotic;
 * both arrive by accident more often than by malice, which is exactly why the defence cannot be a convention.
 *
 * <p>So every read is bounded on four axes at once, each checked as work proceeds rather than afterwards:
 * bytes in, rows out, columns out, and wall-clock time. Failing at the limit with a clear message beats an
 * {@code OutOfMemoryError} that takes the engine down with it.
 *
 * @param maxRows           rows read from a sheet before the read is refused
 * @param maxColumns        columns read from a row before the read is refused
 * @param maxFileBytes      size of an input workbook
 * @param maxProcessingTime wall clock for one attempt
 */
public record ExcelLimits(int maxRows, int maxColumns, long maxFileBytes, Duration maxProcessingTime) {

    /**
     * Sensible defaults for a shared engine.
     *
     * <p>100 000 rows and 512 columns comfortably cover the operational spreadsheets these workflows exist to
     * process, while staying far from the heap a full 1 048 576-row sheet would need. 32 MB matches the order
     * of the platform's other upload ceilings.
     */
    public static final ExcelLimits DEFAULTS =
            new ExcelLimits(100_000, 512, 32L * 1024 * 1024, Duration.ofMinutes(2));

    /** Hard ceilings a node's configuration may not exceed, whatever it asks for. */
    private static final int ABSOLUTE_MAX_ROWS = 1_000_000;
    private static final int ABSOLUTE_MAX_COLUMNS = 16_384;
    private static final long ABSOLUTE_MAX_BYTES = 128L * 1024 * 1024;
    private static final Duration ABSOLUTE_MAX_TIME = Duration.ofMinutes(10);

    /**
     * Reads the limits from node configuration, clamped to the absolute ceilings.
     *
     * <p>Clamped rather than rejected: a workflow author raising a limit is doing something reasonable, and the
     * ceiling exists to stop one node from taking down an engine shared with every other workflow — not to
     * second-guess the author.
     */
    public static ExcelLimits from(NodeConfiguration configuration) {
        int rows = clamp((int) configuration.getLong("maxRows", DEFAULTS.maxRows()), 1, ABSOLUTE_MAX_ROWS);
        int columns = clamp((int) configuration.getLong("maxColumns", DEFAULTS.maxColumns()),
                1, ABSOLUTE_MAX_COLUMNS);
        long bytes = Math.min(Math.max(configuration.getLong("maxFileBytes", DEFAULTS.maxFileBytes()), 1),
                ABSOLUTE_MAX_BYTES);
        long seconds = configuration.getLong("maxProcessingSeconds", DEFAULTS.maxProcessingTime().toSeconds());
        Duration time = Duration.ofSeconds(Math.min(Math.max(seconds, 1), ABSOLUTE_MAX_TIME.toSeconds()));
        return new ExcelLimits(rows, columns, bytes, time);
    }

    /** @throws ExcelException when a workbook is larger than this node permits */
    public void checkFileSize(long bytes) {
        if (bytes > maxFileBytes) {
            throw ExcelException.tooLarge(bytes + " bytes exceeds the " + maxFileBytes + " byte limit.");
        }
    }

    /** @throws ExcelException when a sheet has more rows than this node permits */
    public void checkRowCount(int rows) {
        if (rows > maxRows) {
            throw ExcelException.tooLarge(rows + " rows exceeds the " + maxRows + " row limit.");
        }
    }

    /** @throws ExcelException when a sheet is wider than this node permits */
    public void checkColumnCount(int columns) {
        if (columns > maxColumns) {
            throw ExcelException.tooLarge(columns + " columns exceeds the " + maxColumns + " column limit.");
        }
    }

    /**
     * A clock for one attempt.
     *
     * <p>Handed to loops that check it as they go, rather than wrapping the work in a watchdog thread. An
     * interrupted POI parse can leave a half-built workbook and a leaked file handle; a cooperative check
     * stops at a row boundary, where stopping is safe.
     */
    public Deadline startDeadline() {
        return new Deadline(System.nanoTime() + maxProcessingTime.toNanos(), maxProcessingTime.toMillis());
    }

    /** Cooperative timeout. Cheap enough to call once per row. */
    public record Deadline(long expiresAtNanos, long budgetMillis) {

        /** @throws ExcelException when the attempt has run out of time */
        public void check() {
            if (System.nanoTime() > expiresAtNanos) {
                throw ExcelException.timeout(budgetMillis);
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }
}
