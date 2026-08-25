package com.orchpilot.plugin.excel.exception;

/**
 * A failure with a stable code, so workflows branch on codes rather than on message text.
 *
 * <h2>Messages never describe the host</h2>
 *
 * No message produced here contains a filesystem path, a storage root, or a stack frame. A plugin reaches
 * files only through the engine's accessor and never learns where they live, so there is nothing to leak — and
 * the factory methods below keep it that way by naming only what the user supplied: a sheet name, a column, a
 * cell reference.
 */
public class ExcelException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorCode;
    private final boolean retryable;

    public ExcelException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public ExcelException(String errorCode, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    /** @return whether retrying could plausibly succeed; almost nothing here is, since input does not change */
    public boolean retryable() {
        return retryable;
    }

    // ------------------------------------------------------------------ the standard failures

    public static ExcelException fileNotFound(String fileId) {
        return new ExcelException("EXCEL_FILE_NOT_FOUND",
                "No file '" + fileId + "' is attached to this workflow version.", false);
    }

    public static ExcelException invalidFormat(String detail) {
        return new ExcelException("EXCEL_INVALID_FORMAT",
                "The file is not a readable Excel workbook. " + detail, false);
    }

    public static ExcelException corrupted(Throwable cause) {
        return new ExcelException("EXCEL_CORRUPTED",
                "The workbook could not be opened; it appears to be corrupt or truncated.", false, cause);
    }

    public static ExcelException unsupportedFormat(String extension) {
        return new ExcelException("EXCEL_UNSUPPORTED_FORMAT",
                "Files of type '" + extension + "' are not supported. Use .xlsx, .xls or .xlsm.", false);
    }

    public static ExcelException sheetNotFound(String sheetName, java.util.List<String> available) {
        return new ExcelException("EXCEL_SHEET_NOT_FOUND",
                "The workbook has no sheet named '" + sheetName + "'. Available sheets: "
                        + String.join(", ", available) + ".", false);
    }

    public static ExcelException columnNotFound(String column, java.util.List<String> available) {
        return new ExcelException("EXCEL_COLUMN_NOT_FOUND",
                "The sheet has no column named '" + column + "'. Available columns: "
                        + String.join(", ", available) + ".", false);
    }

    public static ExcelException rowNotFound(String detail) {
        return new ExcelException("EXCEL_ROW_NOT_FOUND", "No row matched: " + detail, false);
    }

    public static ExcelException invalidData(String detail) {
        return new ExcelException("EXCEL_INVALID_DATA", detail, false);
    }

    public static ExcelException validationFailed(int errorCount) {
        return new ExcelException("EXCEL_VALIDATION_FAILED",
                "Validation found " + errorCount + " problem(s). See the 'errors' output for the row, column "
                        + "and reason for each.", false);
    }

    public static ExcelException tooLarge(String detail) {
        return new ExcelException("EXCEL_FILE_TOO_LARGE",
                "The workbook exceeds this node's configured limits: " + detail
                        + " Raise the limit on the node, or split the file first.", false);
    }

    public static ExcelException timeout(long millis) {
        // Retryable: a timeout can be caused by transient load rather than by the file itself.
        return new ExcelException("EXCEL_PROCESSING_TIMEOUT",
                "Processing did not finish within " + millis + " ms.", true);
    }

    public static ExcelException storageError(String detail, Throwable cause) {
        return new ExcelException("EXCEL_STORAGE_ERROR",
                "The workflow file could not be read or written. " + detail, false, cause);
    }

    public static ExcelException permissionDenied(String detail) {
        return new ExcelException("EXCEL_PERMISSION_DENIED", detail, false);
    }
}
