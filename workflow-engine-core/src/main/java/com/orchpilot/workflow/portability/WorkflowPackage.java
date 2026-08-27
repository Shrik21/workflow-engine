package com.orchpilot.workflow.portability;

import com.orchpilot.workflow.forms.FormDefinition;
import com.orchpilot.workflow.model.WorkflowConnection;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.model.WorkflowTrigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plaintext contents of a {@code .orchpilot} file, before encryption and after decryption.
 *
 * <h2>Everything to rebuild the workflow, and nothing secret</h2>
 *
 * The nodes, edges, variables, forms and the plugin versions the workflow needs are all here; a password, an
 * API key, a private key or a stored credential's value is not, and there is no field one could travel in. A
 * credential is exported as a {@link CredentialReference} — its type and display name — never its value, so an
 * importer is told "this needs an AWS Production credential" and picks their own.
 *
 * <h2>Deserialized strictly, never as arbitrary objects</h2>
 *
 * This is a fixed set of fields made of the platform's own model types and JSON scalars. It is read with a
 * Jackson mapper that has default typing disabled, so a malicious payload cannot name a Java class to
 * instantiate — the deserialization-gadget attack the specification calls out is structurally impossible,
 * because nothing here is polymorphic and no type information is read from the document.
 *
 * <h2>The tenant inside is not trusted</h2>
 *
 * {@code sourceTenantId} is recorded for provenance and shown to the importer, but the target tenant of an
 * import is always the authenticated user's, never this field. See {@code WorkflowImportService}.
 */
public class WorkflowPackage {

    /** The package-schema version, distinct from the file-format version. Lets the payload evolve. */
    private int packageVersion = 1;

    private Instant exportedAt;
    private String exportedBy;

    /** Provenance only. Never used to place the import; see the class note. */
    private String sourceWorkflowId;
    private String sourceTenantId;
    private int sourceVersion;

    private String name;
    private String description;

    private List<WorkflowNode> nodes = new ArrayList<>();
    private List<WorkflowConnection> connections = new ArrayList<>();
    private Map<String, Object> variables = new LinkedHashMap<>();
    private List<WorkflowTrigger> triggers = new ArrayList<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();

    private List<FormDefinition> forms = new ArrayList<>();
    private List<PluginDependency> pluginDependencies = new ArrayList<>();
    private List<String> accessGroups = new ArrayList<>();
    private List<CredentialReference> credentialReferences = new ArrayList<>();

    /** Files the exported version had attached, described only — see {@link FileReference}. */
    private List<FileReference> fileReferences = new ArrayList<>();

    /** One plugin the workflow needs, by id and the version it was authored against. */
    public static class PluginDependency {
        private String pluginId;
        private String version;

        public PluginDependency() {
        }

        public PluginDependency(String pluginId, String version) {
            this.pluginId = pluginId;
            this.version = version;
        }

        public String getPluginId() {
            return pluginId;
        }

        public void setPluginId(String pluginId) {
            this.pluginId = pluginId;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }
    }

    /**
     * A pointer to a credential the workflow uses, carrying its type and display name and never its value.
     *
     * @see #credentialReferences
     */
    public static class CredentialReference {
        private String nodeId;
        private String field;
        private String type;
        private String name;

        public String getNodeId() {
            return nodeId;
        }

        public void setNodeId(String nodeId) {
            this.nodeId = nodeId;
        }

        public String getField() {
            return field;
        }

        public void setField(String field) {
            this.field = field;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /**
     * A file the workflow version had attached, described but not carried.
     *
     * <h2>Metadata only, and deliberately no path</h2>
     *
     * The same choice {@link CredentialReference} makes, for the same reason: a package is a portable
     * description of a workflow, not a copy of its data. Carrying the bytes would make an export unbounded in
     * size and would move a copy of every uploaded document to wherever the {@code .orchpilot} file travels,
     * which is a data-handling decision that belongs to the person exporting rather than to the format.
     *
     * <p>There is no {@code relativePath} field, let alone an absolute one. The path is derived on the target
     * system from its own storage root and the imported workflow's id, so a package exported from
     * {@code D:\OrchPilot\data} on Windows imports cleanly under {@code /opt/orchpilot/data} on Linux. The
     * {@code checksum} is carried so that a file re-uploaded after an import can be verified as the same one.
     *
     * @see #fileReferences
     */
    public static class FileReference {
        private String fileName;
        private String contentType;
        private long size;
        private String checksum;
        private int workflowVersion;

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public String getChecksum() {
            return checksum;
        }

        public void setChecksum(String checksum) {
            this.checksum = checksum;
        }

        public int getWorkflowVersion() {
            return workflowVersion;
        }

        public void setWorkflowVersion(int workflowVersion) {
            this.workflowVersion = workflowVersion;
        }
    }

    public int getPackageVersion() {
        return packageVersion;
    }

    public void setPackageVersion(int packageVersion) {
        this.packageVersion = packageVersion;
    }

    public Instant getExportedAt() {
        return exportedAt;
    }

    public void setExportedAt(Instant exportedAt) {
        this.exportedAt = exportedAt;
    }

    public String getExportedBy() {
        return exportedBy;
    }

    public void setExportedBy(String exportedBy) {
        this.exportedBy = exportedBy;
    }

    public String getSourceWorkflowId() {
        return sourceWorkflowId;
    }

    public void setSourceWorkflowId(String sourceWorkflowId) {
        this.sourceWorkflowId = sourceWorkflowId;
    }

    public String getSourceTenantId() {
        return sourceTenantId;
    }

    public void setSourceTenantId(String sourceTenantId) {
        this.sourceTenantId = sourceTenantId;
    }

    public int getSourceVersion() {
        return sourceVersion;
    }

    public void setSourceVersion(int sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<WorkflowNode> getNodes() {
        return nodes;
    }

    public void setNodes(List<WorkflowNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }

    public List<WorkflowConnection> getConnections() {
        return connections;
    }

    public void setConnections(List<WorkflowConnection> connections) {
        this.connections = connections == null ? new ArrayList<>() : connections;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables == null ? new LinkedHashMap<>() : variables;
    }

    public List<WorkflowTrigger> getTriggers() {
        return triggers;
    }

    public void setTriggers(List<WorkflowTrigger> triggers) {
        this.triggers = triggers == null ? new ArrayList<>() : triggers;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : metadata;
    }

    public List<FormDefinition> getForms() {
        return forms;
    }

    public void setForms(List<FormDefinition> forms) {
        this.forms = forms == null ? new ArrayList<>() : forms;
    }

    public List<PluginDependency> getPluginDependencies() {
        return pluginDependencies;
    }

    public void setPluginDependencies(List<PluginDependency> pluginDependencies) {
        this.pluginDependencies = pluginDependencies == null ? new ArrayList<>() : pluginDependencies;
    }

    public List<String> getAccessGroups() {
        return accessGroups;
    }

    public void setAccessGroups(List<String> accessGroups) {
        this.accessGroups = accessGroups == null ? new ArrayList<>() : accessGroups;
    }

    public List<CredentialReference> getCredentialReferences() {
        return credentialReferences;
    }

    public void setCredentialReferences(List<CredentialReference> credentialReferences) {
        this.credentialReferences = credentialReferences == null ? new ArrayList<>() : credentialReferences;
    }

    public List<FileReference> getFileReferences() {
        return fileReferences;
    }

    /** Tolerates null so a package written before this field existed still deserialises. */
    public void setFileReferences(List<FileReference> fileReferences) {
        this.fileReferences = fileReferences == null ? new ArrayList<>() : fileReferences;
    }
}
