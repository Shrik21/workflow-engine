package com.orchpilot.workflow.portability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.forms.FormDefinition;
import com.orchpilot.workflow.forms.FormDefinitionService;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.service.WorkflowService;
import com.orchpilot.workflow.storage.model.FileStatus;
import com.orchpilot.workflow.storage.model.WorkflowFileReference;
import com.orchpilot.workflow.storage.repository.WorkflowFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds an encrypted {@code .orchpilot} file from a stored workflow.
 *
 * <h2>What leaves, and what cannot</h2>
 *
 * The workflow's graph, variables, forms, plugin versions and access groups are packaged. A secret is not,
 * and this is mostly guaranteed by the platform's own design rather than by scrubbing here: a workflow
 * definition never holds a credential value, only a <em>reference</em> to one — a secret name or a credential
 * id the plugin resolves at execution. So exporting the definition as it stands already carries no secrets.
 * On top of that, this pulls those references out into an explicit {@link WorkflowPackage.CredentialReference}
 * list so the importer is shown what it must supply, and never exports the {@code Credentials} content the
 * export dialog offers as an unchecked box — that box is refused here.
 */
@Service
public class WorkflowExportService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExportService.class);

    /** Configuration keys whose value is a reference to a credential, gathered for the import UI. */
    private static final Set<String> CREDENTIAL_REFERENCE_KEYS = Set.of(
            "credentialid", "connectionid", "passwordsecret", "bearertokensecret", "apikeysecret",
            "presharedkeysecret", "privatekeysecret");

    private final WorkflowService workflowService;
    private final FormDefinitionService formService;
    private final AuditService auditService;
    /** Read directly: the package needs file metadata, not the storage machinery that serves file content. */
    private final WorkflowFileRepository fileRepository;
    private final PackageCrypto crypto = new PackageCrypto();
    private final PackageCodec codec = new PackageCodec();
    private final ObjectMapper keyMetaMapper = new ObjectMapper();
    private final byte[] masterKey;

    public WorkflowExportService(WorkflowService workflowService, FormDefinitionService formService,
                                 AuditService auditService, WorkflowFileRepository fileRepository,
                                 WorkflowEngineProperties properties) {
        this.workflowService = workflowService;
        this.formService = formService;
        this.auditService = auditService;
        this.fileRepository = fileRepository;
        String configured = properties.getSecrets().getMasterKey();
        this.masterKey = configured == null || configured.isBlank() ? null
                : Base64.getDecoder().decode(configured.trim());
    }

    /**
     * What the caller asked to include and how to protect it.
     *
     * @param includeForms      package the referenced form definitions
     * @param includeVariables  package the workflow variables
     * @param includePluginDependencies package the plugin id/version list
     * @param includePermissions package the access groups
     * @param encryptionMode    {@code PLATFORM} or {@code PASSWORD}
     * @param password          the export password, required for {@code PASSWORD} mode, ignored otherwise
     */
    public record ExportOptions(boolean includeForms, boolean includeVariables,
                                boolean includePluginDependencies, boolean includePermissions,
                                String encryptionMode, char[] password) {
    }

    /** The exported file and a name for it. */
    public record ExportResult(String fileName, byte[] bytes) {
    }

    /**
     * Exports a workflow.
     *
     * @param workflowId the workflow
     * @param options    what to include and how to protect it
     * @param actor      who is exporting, for the package provenance and the audit record
     * @return the file
     */
    public ExportResult export(String workflowId, ExportOptions options, String actor) {
        Workflow workflow = workflowService.get(workflowId);
        WorkflowPackage workflowPackage = build(workflow, options, actor);

        byte[] payload = codec.serialize(workflowPackage);
        byte[] file = encrypt(payload, options);

        auditService.record(actor, "WORKFLOW_EXPORT", "WORKFLOW", workflowId, "OK",
                Map.of("encryptionMode", modeName(options), "sizeBytes", file.length,
                        "includedForms", workflowPackage.getForms().size(),
                        "pluginDependencies", workflowPackage.getPluginDependencies().size()));

        return new ExportResult(fileName(workflow), file);
    }

    private WorkflowPackage build(Workflow workflow, ExportOptions options, String actor) {
        WorkflowPackage pkg = new WorkflowPackage();
        pkg.setExportedAt(Instant.now());
        pkg.setExportedBy(actor);
        pkg.setSourceWorkflowId(workflow.getId());
        pkg.setSourceTenantId(workflow.getTenantId());
        pkg.setSourceVersion(workflow.getVersion());
        pkg.setName(workflow.getName());
        pkg.setDescription(workflow.getDescription());

        pkg.setNodes(new ArrayList<>(workflow.getNodes()));
        pkg.setConnections(new ArrayList<>(workflow.getConnections()));
        pkg.setTriggers(new ArrayList<>(workflow.getTriggers()));
        pkg.setMetadata(workflow.getMetadata());

        if (options.includeVariables()) {
            pkg.setVariables(workflow.getVariables());
        }
        if (options.includePluginDependencies()) {
            pkg.setPluginDependencies(pluginDependencies(workflow));
        }
        if (options.includePermissions()) {
            pkg.setAccessGroups(new ArrayList<>(workflow.getAccessGroups()));
        }
        if (options.includeForms()) {
            pkg.setForms(referencedForms(workflow));
        }
        pkg.setCredentialReferences(credentialReferences(workflow));
        pkg.setFileReferences(fileReferences(workflow));
        return pkg;
    }

    /**
     * Describes the files attached to the version being exported.
     *
     * <p>Metadata only — no content and no path, the same contract credentials get. The importer sees what the
     * workflow expects and is told to re-upload it; see {@link WorkflowPackage.FileReference} for why the bytes
     * do not travel.
     *
     * <p>Best-effort: a deployment with no storage configured has no files to describe, and that must not stop
     * an export of a workflow that never had any.
     */
    private List<WorkflowPackage.FileReference> fileReferences(Workflow workflow) {
        List<WorkflowPackage.FileReference> references = new ArrayList<>();
        try {
            for (WorkflowFileReference file
                    : fileRepository.findByWorkflowIdAndStatus(workflow.getId(), FileStatus.ACTIVE)) {
                WorkflowPackage.FileReference reference = new WorkflowPackage.FileReference();
                reference.setFileName(file.getOriginalFileName());
                reference.setContentType(file.getContentType());
                reference.setSize(file.getSize());
                reference.setChecksum(file.getChecksum());
                reference.setWorkflowVersion(file.getWorkflowVersion());
                references.add(reference);
            }
        } catch (RuntimeException ex) {
            log.warn("Could not list attached files while exporting workflow {}: {}",
                    workflow.getId(), ex.toString());
        }
        return references;
    }

    /** The distinct plugin id/version pairs the workflow's plugin nodes depend on. */
    private List<WorkflowPackage.PluginDependency> pluginDependencies(Workflow workflow) {
        Set<String> seen = new LinkedHashSet<>();
        List<WorkflowPackage.PluginDependency> dependencies = new ArrayList<>();
        for (WorkflowNode node : workflow.getNodes()) {
            if (node.getPluginId() != null && !node.getPluginId().isBlank()) {
                String key = node.getPluginId() + ":" + node.getPluginVersion();
                if (seen.add(key)) {
                    dependencies.add(new WorkflowPackage.PluginDependency(node.getPluginId(),
                            node.getPluginVersion()));
                }
            }
        }
        return dependencies;
    }

    /** The form definitions the workflow's form nodes reference, snapshotted for re-creation on import. */
    private List<FormDefinition> referencedForms(Workflow workflow) {
        Set<String> formIds = new LinkedHashSet<>();
        for (WorkflowNode node : workflow.getNodes()) {
            if (node.getFormId() != null && !node.getFormId().isBlank()) {
                formIds.add(node.getFormId());
            }
        }
        List<FormDefinition> forms = new ArrayList<>();
        for (String formId : formIds) {
            try {
                forms.add(formService.get(formId));
            } catch (RuntimeException ex) {
                // A node may reference a form id that predates the form designer, or one since deleted. That is
                // not a reason to fail the export; the import will flag the missing form.
                log.debug("Form {} referenced by a node could not be exported: {}", formId, ex.getMessage());
            }
        }
        return forms;
    }

    /**
     * Pulls credential references out of node configuration for the import UI.
     *
     * <p>The value recorded is the reference the workflow already holds — a secret name or a credential id —
     * which is an identifier, not a secret: the actual credential value lives in the secret store and is never
     * in a workflow definition. The importer maps each reference to one of their own.
     */
    private List<WorkflowPackage.CredentialReference> credentialReferences(Workflow workflow) {
        List<WorkflowPackage.CredentialReference> references = new ArrayList<>();
        for (WorkflowNode node : workflow.getNodes()) {
            for (Map.Entry<String, Object> entry : node.getConfiguration().entrySet()) {
                if (CREDENTIAL_REFERENCE_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))
                        && entry.getValue() != null && !String.valueOf(entry.getValue()).isBlank()) {
                    WorkflowPackage.CredentialReference reference = new WorkflowPackage.CredentialReference();
                    reference.setNodeId(node.getId());
                    reference.setField(entry.getKey());
                    reference.setType(node.getPluginId() == null ? node.getType() : node.getPluginId());
                    reference.setName(String.valueOf(entry.getValue()));
                    references.add(reference);
                }
            }
        }
        return references;
    }

    /** Encrypts the payload into the binary file, per the chosen mode. */
    private byte[] encrypt(byte[] payload, ExportOptions options) {
        byte[] nonce = crypto.newNonce();
        if (isPassword(options)) {
            if (options.password() == null || options.password().length == 0) {
                throw new IllegalArgumentException("A password is required for password-protected export.");
            }
            byte[] salt = crypto.newSalt();
            byte[] key = crypto.deriveKey(options.password(), salt, PackageCrypto.ARGON2_ITERATIONS,
                    PackageCrypto.ARGON2_MEMORY_KB, PackageCrypto.ARGON2_PARALLELISM);
            try {
                byte[] ciphertext = crypto.encrypt(key, nonce, payload);
                byte[] keyMeta = writeKeyMeta(Map.of(
                        "salt", base64(salt),
                        "iterations", PackageCrypto.ARGON2_ITERATIONS,
                        "memoryKb", PackageCrypto.ARGON2_MEMORY_KB,
                        "parallelism", PackageCrypto.ARGON2_PARALLELISM));
                return OrchPilotFile.write(OrchPilotFile.MODE_PASSWORD, keyMeta, nonce, ciphertext);
            } finally {
                PackageCrypto.wipe(key);
            }
        }

        // Platform mode: envelope encryption under the master key.
        if (masterKey == null) {
            throw new IllegalStateException("Platform-managed export needs workflow.engine.secrets.master-key "
                    + "to be configured. Use password-protected export instead, or set the master key.");
        }
        byte[] contentKey = crypto.newContentKey();
        try {
            byte[] ciphertext = crypto.encrypt(contentKey, nonce, payload);
            PackageCrypto.Sealed wrapped = crypto.wrapKey(masterKey, contentKey);
            byte[] keyMeta = writeKeyMeta(Map.of(
                    "wrappedKey", base64(wrapped.ciphertext()),
                    "wrapNonce", base64(wrapped.nonce())));
            return OrchPilotFile.write(OrchPilotFile.MODE_PLATFORM, keyMeta, nonce, ciphertext);
        } finally {
            PackageCrypto.wipe(contentKey);
        }
    }

    private byte[] writeKeyMeta(Map<String, Object> meta) {
        try {
            return keyMetaMapper.writeValueAsBytes(meta);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write key metadata", ex);
        }
    }

    private boolean isPassword(ExportOptions options) {
        return "PASSWORD".equalsIgnoreCase(options.encryptionMode());
    }

    private String modeName(ExportOptions options) {
        return isPassword(options) ? "PASSWORD" : "PLATFORM";
    }

    private static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private static String fileName(Workflow workflow) {
        String base = workflow.getName() == null || workflow.getName().isBlank() ? "workflow"
                : workflow.getName().trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("(^-|-$)", "");
        return (base.isBlank() ? "workflow" : base) + ".orchpilot";
    }
}
