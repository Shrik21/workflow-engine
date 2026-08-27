package com.orchpilot.plugin.excel.model;

/**
 * The type a cell's value was determined to be.
 *
 * <h2>Finer than Excel's own types, on purpose</h2>
 *
 * Excel stores every number as a double and distinguishes a date only by its display format. A workflow
 * branching on a value needs more than "numeric": {@code Salary > 50000} and {@code HireDate > 2020-01-01} are
 * different questions, and a quantity of 3 is not the same as a quantity of 3.5 when it is about to become a
 * row count. So the reader narrows a numeric cell to {@link #INTEGER}, {@link #LONG}, {@link #DECIMAL} or a
 * date type, and reports which it chose.
 */
public enum CellType {

    STRING,

    /** A whole number that fits in an {@code int}. */
    INTEGER,

    /** A whole number too large for an {@code int}. */
    LONG,

    /** A number with a fractional part. */
    DECIMAL,

    BOOLEAN,

    /** A date with no meaningful time component; rendered as {@code yyyy-MM-dd}. */
    DATE,

    /** A date carrying a time; rendered as an ISO-8601 instant-style local date-time. */
    DATETIME,

    /**
     * A formula cell whose formula text was requested.
     *
     * <p>When a caller asks for calculated values instead, the cell reports the type of its <em>result</em>
     * rather than this — otherwise every computed column in a workbook would come back as an opaque
     * {@code FORMULA} and be useless to a filter.
     */
    FORMULA,

    /** Empty, or present but containing nothing. */
    BLANK,

    /** An Excel error such as {@code #DIV/0!} or {@code #N/A}, carried through rather than thrown. */
    ERROR;

    /** @return whether values of this type can be compared as numbers */
    public boolean isNumeric() {
        return this == INTEGER || this == LONG || this == DECIMAL;
    }

    /** @return whether values of this type are points in time */
    public boolean isTemporal() {
        return this == DATE || this == DATETIME;
    }
}
