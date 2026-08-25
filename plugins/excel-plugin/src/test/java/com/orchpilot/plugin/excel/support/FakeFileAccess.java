package com.orchpilot.plugin.excel.support;

import com.orchpilot.workflow.sdk.context.WorkflowFileAccess;
import com.orchpilot.workflow.sdk.context.WorkflowFileHandle;
import com.orchpilot.workflow.sdk.exception.PluginException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link WorkflowFileAccess} for tests.
 *
 * <p>Mirrors the real accessor's two contracts that the plugin depends on: a file id that was never stored is
 * simply absent (there is no cross-workflow lookup to fake, because the real interface has no argument for
 * one), and {@link #write} always creates a new file rather than replacing an existing one.
 */
public final class FakeFileAccess implements WorkflowFileAccess {

    private final Map<String, byte[]> content = new LinkedHashMap<>();
    private final Map<String, WorkflowFileHandle> handles = new LinkedHashMap<>();
    private int nextId = 1;

    /** Seeds a file as though it had been uploaded, and returns its id. */
    public String seed(String fileName, byte[] bytes) {
        String fileId = "FILE-" + nextId++;
        content.put(fileId, bytes);
        handles.put(fileId, new WorkflowFileHandle(fileId, fileName, contentType(fileName), bytes.length,
                checksum(bytes), 1, Instant.now(), "tester"));
        return fileId;
    }

    /** @return the bytes stored under an id, for asserting on what an operation produced */
    public byte[] bytesOf(String fileId) {
        return content.get(fileId);
    }

    /** @return every file written during the test, in order */
    public List<WorkflowFileHandle> written() {
        List<WorkflowFileHandle> result = new ArrayList<>();
        for (Map.Entry<String, WorkflowFileHandle> entry : handles.entrySet()) {
            if (entry.getKey().startsWith("OUT-")) {
                result.add(entry.getValue());
            }
        }
        return result;
    }

    @Override
    public String workflowId() {
        return "WF-100";
    }

    @Override
    public int workflowVersion() {
        return 1;
    }

    @Override
    public InputStream open(String fileId) {
        byte[] bytes = content.get(fileId);
        if (bytes == null) {
            throw new PluginException("FILE_NOT_FOUND", "No file '" + fileId + "'.");
        }
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public Optional<WorkflowFileHandle> find(String fileId) {
        return Optional.ofNullable(handles.get(fileId));
    }

    @Override
    public List<WorkflowFileHandle> list() {
        return new ArrayList<>(handles.values());
    }

    @Override
    public WorkflowFileHandle write(String fileName, String contentType, InputStream stream) {
        byte[] bytes = drain(stream);
        // "OUT-" so a test can tell what the plugin produced from what it was given.
        String fileId = "OUT-" + nextId++;
        content.put(fileId, bytes);
        WorkflowFileHandle handle = new WorkflowFileHandle(fileId, fileName, contentType, bytes.length,
                checksum(bytes), 1, Instant.now(), "tester");
        handles.put(fileId, handle);
        return handle;
    }

    private static byte[] drain(InputStream stream) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            stream.transferTo(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String checksum(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String contentType(String fileName) {
        return fileName.endsWith(".csv") ? "text/csv"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
}
