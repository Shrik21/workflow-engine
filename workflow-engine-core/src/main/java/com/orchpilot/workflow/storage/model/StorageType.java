package com.orchpilot.workflow.storage.model;

/**
 * Where a file physically lives.
 *
 * <p>Stored on every {@link WorkflowFileReference}, not only on the settings document, so a reference always
 * knows which provider can resolve it. That matters the day a deployment migrates from local disk to object
 * storage: files written before the switch keep resolving through the provider that wrote them, instead of
 * every historical reference breaking at once.
 */
public enum StorageType {

    /** A directory on the machine (or the volume mounted into the container) running the engine. */
    LOCAL(true),

    /** Amazon S3 or an S3-compatible endpoint. Not implemented in this phase. */
    S3(false),

    /** Azure Blob Storage. Not implemented in this phase. */
    AZURE_BLOB(false),

    /** Google Cloud Storage. Not implemented in this phase. */
    GCP_STORAGE(false),

    /**
     * A network share mounted into the filesystem.
     *
     * <p>Listed separately from {@link #LOCAL} even though a mounted share is reachable through the same file
     * APIs, because its failure modes are not local ones — a stalled mount blocks rather than erroring, and free
     * space belongs to a server somebody else runs.
     */
    NFS(false);

    private final boolean implemented;

    StorageType(boolean implemented) {
        this.implemented = implemented;
    }

    /**
     * @return whether a provider for this type ships today; the settings endpoint refuses to save a type that
     *         has no provider rather than accepting configuration that would fail at the first upload
     */
    public boolean isImplemented() {
        return implemented;
    }
}
