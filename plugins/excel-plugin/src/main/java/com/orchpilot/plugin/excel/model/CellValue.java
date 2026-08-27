package com.orchpilot.plugin.excel.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * One cell: its value, the type that value was judged to be, and — for a formula — the formula text.
 *
 * <h2>Immutable, and comparable without knowing its type</h2>
 *
 * Every operation in this plugin (filter, sort, compare, aggregate) has to work on values whose types differ
 * row to row, because real spreadsheets are not homogeneous. {@link #compareTo} therefore defines a total
 * order across types rather than throwing when two cells disagree — a column of mostly numbers with one
 * stray text entry still sorts, instead of failing the whole node.
 *
 * @param value     the typed Java value: String, Long, BigDecimal, Boolean, LocalDate, LocalDateTime, or null
 * @param type      what {@link #value} was determined to be
 * @param formula   the formula text without its leading {@code =}, or null for a non-formula cell
 */
public record CellValue(Object value, CellType type, String formula) implements Comparable<CellValue> {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public static final CellValue BLANK = new CellValue(null, CellType.BLANK, null);

    public static CellValue of(String value) {
        return value == null ? BLANK : new CellValue(value, CellType.STRING, null);
    }

    public static CellValue of(long value) {
        return new CellValue(value, value == (int) value ? CellType.INTEGER : CellType.LONG, null);
    }

    public static CellValue of(BigDecimal value) {
        return value == null ? BLANK : new CellValue(value, CellType.DECIMAL, null);
    }

    /**
     * A computed number, narrowed to a whole number when it is one.
     *
     * <p>The single place arithmetic results become cells. Without it, {@code Salary * 12} produces a DECIMAL
     * that renders as {@code 600000.0} in a report or a CSV — the same "everything is a double" problem the
     * reader exists to fix, reintroduced on the way out. Every engine that computes a value uses this rather
     * than {@link #of(BigDecimal)} directly.
     */
    public static CellValue computed(BigDecimal value) {
        if (value == null) {
            return BLANK;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            try {
                return of(stripped.longValueExact());
            } catch (ArithmeticException ex) {
                // Beyond long range; keep it as a decimal rather than losing precision.
                return of(stripped);
            }
        }
        return of(stripped);
    }

    public static CellValue of(boolean value) {
        return new CellValue(value, CellType.BOOLEAN, null);
    }

    public static CellValue of(LocalDate value) {
        return value == null ? BLANK : new CellValue(value, CellType.DATE, null);
    }

    public static CellValue of(LocalDateTime value) {
        return value == null ? BLANK : new CellValue(value, CellType.DATETIME, null);
    }

    public static CellValue error(String code) {
        return new CellValue(code, CellType.ERROR, null);
    }

    /** A formula cell reported as its text rather than its result. */
    public static CellValue formula(String formulaText) {
        return new CellValue("=" + formulaText, CellType.FORMULA, formulaText);
    }

    /** A formula cell reported as its cached result, keeping the formula alongside for the output. */
    public static CellValue calculated(CellValue result, String formulaText) {
        return new CellValue(result.value(), result.type(), formulaText);
    }

    public boolean isBlank() {
        return type == CellType.BLANK || value == null
                || (value instanceof String text && text.isBlank());
    }

    /**
     * The value as it should appear in JSON output.
     *
     * <p>Dates become ISO strings rather than remaining {@code LocalDate} objects, because the result travels
     * through the engine's JSON serialisation into a workflow variable, and a consistent, sortable, unambiguous
     * date format is what makes {@code ${row.HireDate}} usable in a later node.
     */
    public Object toJson() {
        return switch (type) {
            case DATE -> value instanceof LocalDate date ? date.format(DATE) : value;
            case DATETIME -> value instanceof LocalDateTime dateTime ? dateTime.format(DATE_TIME) : value;
            case DECIMAL -> value instanceof BigDecimal decimal ? decimal.doubleValue() : value;
            case BLANK -> null;
            default -> value;
        };
    }

    /** @return the value rendered as text, never null; a blank cell renders as an empty string */
    public String asText() {
        Object json = toJson();
        return json == null ? "" : String.valueOf(json);
    }

    /** @return the value as a number, or null when it is not numeric and cannot be parsed as one */
    public BigDecimal asNumber() {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        if (value instanceof Boolean flag) {
            return flag ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        // A numeric column read from a text-formatted spreadsheet is extremely common; refusing to compare it
        // would make filters mysteriously match nothing.
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Orders cells across mixed types.
     *
     * <p>Blanks sort last, numbers and dates compare within their own kind, and anything else falls back to a
     * case-insensitive text comparison. Two numerically-comparable cells compare as numbers even when one is
     * stored as text, which is what makes sorting a scraped spreadsheet behave the way a person expects.
     */
    @Override
    public int compareTo(CellValue other) {
        if (isBlank() && other.isBlank()) {
            return 0;
        }
        // Blanks last regardless of direction, so a sort never buries the populated rows.
        if (isBlank()) {
            return 1;
        }
        if (other.isBlank()) {
            return -1;
        }
        if (type.isTemporal() && other.type.isTemporal()) {
            return temporal().compareTo(other.temporal());
        }
        BigDecimal mine = asNumber();
        BigDecimal theirs = other.asNumber();
        if (mine != null && theirs != null) {
            return mine.compareTo(theirs);
        }
        return asText().compareToIgnoreCase(other.asText());
    }

    /** Normalises a DATE to the start of its day so it can be compared with a DATETIME. */
    private LocalDateTime temporal() {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        return LocalDateTime.MIN;
    }
}
