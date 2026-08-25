package com.orchpilot.workflow.storage.util;

/**
 * Builds the relative paths that locate a file under the storage root.
 *
 * <h2>Only controlled values become path segments</h2>
 *
 * Every segment here comes from the platform — tenant id, workflow id, version number, generated file id — and
 * each is asserted safe by {@link FilenameSanitizer#requireSafeSegment}. The single user-influenced component,
 * the filename, arrives already sanitised and is prefixed with a generated id. There is deliberately no method
 * on this class that accepts a caller-supplied path fragment, because that is the shape
 * ({@code basePath + userInput}) the whole module exists to avoid.
 *
 * <h2>Always POSIX-style</h2>
 *
 * Stored paths use {@code /} on every platform. A path written on Windows must resolve on Linux after a restore
 * or a container migration, and mixing separators in the database would make that a data-migration problem rather
 * than a no-op. Conversion to the platform's separators happens once, in the provider, at the moment of use.
 *
 * <h2>The tenant segment</h2>
 *
 * Present only when a tenant id is. That keeps a single-tenant deployment's layout exactly as documented
 * ({@code workflows/…}) while giving a multi-tenant one a hard prefix boundary. Because each file stores its own
 * relative path, enabling tenancy later does not strand files written before it.
 */
public final class StoragePaths {

    public static final String WORKFLOWS = "workflows";
    public static final String TENANTS = "tenants";
    public static final String FILES = "files";

    private StoragePaths() {
    }

    /**
     * @param tenantId        owning tenant, or null for a single-tenant deployment
     * @param workflowId      the workflow
     * @param workflowVersion the version, 1-based
     * @return the relative directory holding that version's files, e.g. {@code workflows/WF-1/v3/files}
     */
    public static String versionDirectory(String tenantId, String workflowId, int workflowVersion) {
        if (workflowVersion < 1) {
            throw new IllegalArgumentException("Workflow version must be 1 or greater");
        }
        FilenameSanitizer.requireSafeSegment(workflowId, "workflowId");

        StringBuilder path = new StringBuilder();
        if (tenantId != null && !tenantId.isBlank()) {
            FilenameSanitizer.requireSafeSegment(tenantId, "tenantId");
            path.append(TENANTS).append('/').append(tenantId).append('/');
        }
        path.append(WORKFLOWS).append('/').append(workflowId)
                .append("/v").append(workflowVersion)
                .append('/').append(FILES);
        return path.toString();
    }

    /**
     * @param storedFileName the {@code {fileId}-{sanitised}} name, already safe
     * @return the full relative path of one file
     */
    public static String filePath(String tenantId, String workflowId, int workflowVersion,
                                  String storedFileName) {
        FilenameSanitizer.requireSafeSegment(storedFileName, "storedFileName");
        return versionDirectory(tenantId, workflowId, workflowVersion) + "/" + storedFileName;
    }

    /**
     * Builds the physical filename.
     *
     * <p>The file id leads, so two people uploading {@code invoice.pdf} at the same moment cannot collide — which
     * is why the original name is never used alone. Keeping the readable part after it means an administrator
     * looking at the directory can still tell what a file is.
     *
     * @param fileId           generated identifier
     * @param originalFileName the client's name, sanitised here
     */
    public static String storedFileName(String fileId, String originalFileName) {
        FilenameSanitizer.requireSafeSegment(fileId, "fileId");
        return fileId + "-" + FilenameSanitizer.sanitize(originalFileName);
    }

    /**
     * Rejects a stored relative path that is not the shape this class produces.
     *
     * <p>Applied to values read back from the database and from import packages, because a reference is only
     * trustworthy if nothing has edited the collection directly. Cheap, and it turns a tampered document into a
     * clear rejection rather than a filesystem access outside the root.
     *
     * @param relativePath the candidate
     * @return the path unchanged
     * @throws IllegalArgumentException when it is absolute, contains traversal, or uses backslashes
     */
    public static String requireRelative(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("The stored file path is missing");
        }
        if (relativePath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("A stored file path must use forward slashes");
        }
        if (relativePath.startsWith("/") || relativePath.contains(":")) {
            throw new IllegalArgumentException("A stored file path must be relative");
        }
        for (String segment : relativePath.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("A stored file path must not contain traversal segments");
            }
        }
        return relativePath;
    }
}
