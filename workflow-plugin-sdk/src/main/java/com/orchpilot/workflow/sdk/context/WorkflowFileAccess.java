package com.orchpilot.workflow.sdk.context;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * A plugin's only route to workflow files, scoped to the workflow being executed.
 *
 * <h2>Why there is no {@code workflowId} parameter</h2>
 *
 * This is the security design, not an omission. The engine hands a plugin an instance already bound to the
 * execution's workflow and version, so a plugin <em>cannot express</em> a request for another workflow's file:
 * there is no argument to put the wrong id into, and therefore no cross-workflow read to get wrong. The
 * alternative — a {@code read(workflowId, version, fileId)} method plus a check — puts an
 * insecure-direct-object-reference one forgotten line away, in every plugin, forever.
 *
 * <p>It follows that this lives on {@link com.orchpilot.workflow.sdk.node.NodeExecutionContext} rather than on
 * {@link PluginContext}: a plugin context is per plugin version and knows no workflow, while an execution
 * context knows exactly one.
 *
 * <h2>Streams, not byte arrays</h2>
 *
 * {@link #open} returns a stream and {@link #write} takes one, so a large spreadsheet never has to exist as a
 * byte array on the heap. The caller closes what it is given.
 *
 * <h2>Writing creates, never overwrites</h2>
 *
 * {@link #write} always produces a <em>new</em> file with a new id. There is deliberately no update-in-place:
 * a workflow that rewrote its input would destroy the evidence of what it started from, and a retry would then
 * operate on already-transformed data. A plugin that wants "a new version of this file" writes a new file and
 * returns its handle; the original stays readable.
 *
 * @since 1.0.0
 */
public interface WorkflowFileAccess {

    /** @return the workflow this accessor is bound to, for logging and messages */
    String workflowId();

    /** @return the workflow version this accessor is bound to */
    int workflowVersion();

    /**
     * Opens a file for reading.
     *
     * @param fileId the file's id, as carried on a file reference
     * @return a stream the caller must close
     * @throws com.orchpilot.workflow.sdk.exception.PluginException when the file does not belong to this
     *         workflow version, has been deleted, or its content is missing from storage
     */
    InputStream open(String fileId);

    /**
     * Reads a file's metadata without opening it.
     *
     * @return the handle, or empty when no such file belongs to this workflow version
     */
    Optional<WorkflowFileHandle> find(String fileId);

    /** @return every active file attached to this workflow version, newest first */
    List<WorkflowFileHandle> list();

    /**
     * Stores new content as a new file against this workflow version.
     *
     * @param fileName    the name to record; sanitised by the engine, never used to build a path
     * @param contentType MIME type, or null for {@code application/octet-stream}
     * @param content     the bytes; not closed by this method
     * @return the handle for the newly created file
     * @throws com.orchpilot.workflow.sdk.exception.PluginException when storage is not configured or the write
     *         fails
     */
    WorkflowFileHandle write(String fileName, String contentType, InputStream content);
}
