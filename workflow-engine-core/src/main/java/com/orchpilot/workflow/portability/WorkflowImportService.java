package com.orchpilot.workflow.portability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.dto.WorkflowRequest;
import com.orchpilot.workflow.forms.FormDefinition;
import com.orchpilot.workflow.forms.FormDefinitionService;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowConnection;
import com.orchpilot.workflow.model.WorkflowNode;
import com.orchpilot.workflow.repository.WorkflowRepository;
import com.orchpilot.workflow.service.AuditService;
import com.orchpilot.workflow.service.WorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads an encrypted {@code .orchpilot} file, checks it, and imports the workflow — as new resources, into the
 * importer's own tenant.
 *
 * <h2>The target tenant is the caller's, never the file's</h2>
 *
 * The package records the tenant it came from, but that is provenance and nothing more. The import is created
 * through {@link WorkflowService#create}, which stamps ownership and tenant from the authenticated user, so a
 * crafted package claiming another tenant's id cannot place a workflow there — the security boundary is the
 * session, not the ciphertext. Every id is regenerated too, so nothing attaches to an existing document.
 *
 * <h2>Validate, then import</h2>
 *
 * {@link #validate} does everything short of writing: decrypt, authenticate, deserialize strictly, resolve
 * plugin dependencies, list credential references and access groups, and detect an id conflict — so the UI can
 * show a preview and the operator can decide before anything is created. {@link #importWorkflow} repeats the
 * decrypt (the plaintext is never held between calls) and then writes.
 */
@Service
public class WorkflowImportService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowImportService.class);

    private final WorkflowService workflowService;
    private final WorkflowRepository workflowRepository;
    private final FormDefinitionService formService;
    private final PluginDependencyResolver pluginResolver;
    private final AuditService auditService;

    private final PackageCrypto crypto = new PackageCrypto();
    private final PackageCodec codec = new PackageCodec();
    private final ObjectMapper keyMetaMapper = new ObjectMapper();
    private final byte[] masterKey;
    private final long maxFileBytes;

    public WorkflowImportService(WorkflowService workflowService, WorkflowRepository workflowRepository,
                                 FormDefinitionService formService, PluginDependencyResolver pluginResolver,
                                 AuditService auditService, WorkflowEngineProperties properties) {
        this.workflowService = workflowService;
        this.workflowRepository = workflowRepository;
        this.formService = formService;
        this.pluginResolver = pluginResolver;
        this.auditService = auditService;
        String configured = properties.getSecrets().getMasterKey();
        this.masterKey = configured == null || configured.isBlank() ? null
                : Base64.getDecoder().decode(configured.trim());
        this.maxFileBytes = properties.getImportExport().getMaxFileBytes();
    }

    /** The preview a caller sees before importing. */
    public record ValidationResult(boolean valid, String name, String description, int sourceVersion,
                                   String exportedBy, List<PluginDependencyResolver.Result> plugins,
                                   List<String> missingPlugins,
                                   List<WorkflowPackage.CredentialReference> credentialReferences,
                                   List<WorkflowPackage.FileReference> fileReferences,
                                   List<String> accessGroups, boolean conflict, List<String> warnings,
                                   List<String> errors) {
    }

    /** The outcome of an import. */
    public record ImportResult(boolean success, String workflowId, String workflowName,
                               List<String> missingPlugins, List<String> warnings) {
    }

    /**
     * Validates a file without importing.
     *
     * @param file     the {@code .orchpilot} bytes
     * @param password the export password, for a password-protected file
     * @param actor    who is validating, for the audit record
     * @return the preview
     */
    public ValidationResult validate(byte[] file, char[] password, String actor) {
        try {
            WorkflowPackage pkg = decrypt(file, password);
            List<PluginDependencyResolver.Result> plugins = pluginResolver.resolve(pkg.getPluginDependencies());
            List<String> missing = plugins.stream()
                    .filter(result -> result.compatibility() != PluginDependencyResolver.Compatibility.COMPATIBLE)
                    .map(PluginDependencyResolver.Result::pluginId)
                    .toList();
            boolean conflict = pkg.getSourceWorkflowId() != null
                    && workflowRepository.findById(pkg.getSourceWorkflowId()).isPresent();

            List<String> warnings = new ArrayList<>();
            if (!missing.isEmpty()) {
                warnings.add("Some plugins are missing or out of date: " + String.join(", ", missing) + ".");
            }
            if (!pkg.getCredentialReferences().isEmpty()) {
                warnings.add(pkg.getCredentialReferences().size() + " credential reference(s) must be mapped "
                        + "to credentials in this environment; none were exported.");
            }
            if (!pkg.getFileReferences().isEmpty()) {
                // The importer has to know the workflow expects files, or a node will fail at run time with a
                // FILE_NOT_FOUND that gives no hint the package never carried them.
                warnings.add(pkg.getFileReferences().size() + " attached file(s) were described but not "
                        + "included in the package; re-upload them to the imported workflow's version. "
                        + "Their checksums are listed so you can confirm you uploaded the same files.");
            }

            auditService.record(actor, "WORKFLOW_IMPORT_VALIDATED", "WORKFLOW",
                    pkg.getSourceWorkflowId(), "OK", Map.of("name", String.valueOf(pkg.getName())));

            return new ValidationResult(true, pkg.getName(), pkg.getDescription(), pkg.getSourceVersion(),
                    pkg.getExportedBy(), plugins, missing, pkg.getCredentialReferences(),
                    pkg.getFileReferences(), pkg.getAccessGroups(), conflict, warnings, List.of());
        } catch (PackageIntegrityException | IllegalArgumentException ex) {
            auditService.record(actor, "WORKFLOW_IMPORT_FAILED", "WORKFLOW", null, "FAILED",
                    Map.of("reason", "validation"));
            return new ValidationResult(false, null, null, 0, null, List.of(), List.of(), List.of(),
                    List.of(), List.of(), false, List.of(), List.of(ex.getMessage()));
        }
    }

    /**
     * Imports a validated file, creating a new workflow (and its forms) in the caller's tenant.
     *
     * @param file     the {@code .orchpilot} bytes
     * @param password the export password, for a password-protected file
     * @param actor    who is importing
     * @return the outcome
     */
    public ImportResult importWorkflow(byte[] file, char[] password, String actor) {
        WorkflowPackage pkg;
        try {
            pkg = decrypt(file, password);
        } catch (RuntimeException ex) {
            auditService.record(actor, "WORKFLOW_IMPORT_FAILED", "WORKFLOW", null, "FAILED",
                    Map.of("reason", "decrypt"));
            throw ex;
        }

        // Fresh ids for every node and edge before anything is created; see IdRemapper.
        IdRemapper remapper = new IdRemapper();
        remapper.remapNodes(pkg.getNodes());
        remapper.remapConnections(pkg.getConnections());

        // Re-create the forms with new ids and repoint the nodes at them, so an import never reuses another
        // environment's — or another tenant's — form id.
        Map<String, String> formIdMapping = importForms(pkg.getForms());
        for (WorkflowNode node : pkg.getNodes()) {
            if (node.getFormId() != null && formIdMapping.containsKey(node.getFormId())) {
                node.setFormId(formIdMapping.get(node.getFormId()));
            }
        }

        WorkflowRequest request = toRequest(pkg);
        // create() assigns a new workflow id and stamps the tenant/owner from the authenticated user — the
        // tenant isolation guarantee — and records WORKFLOW_CREATED itself.
        Workflow created = workflowService.create(request, actor);

        List<String> missing = pluginResolver.resolve(pkg.getPluginDependencies()).stream()
                .filter(result -> result.compatibility() != PluginDependencyResolver.Compatibility.COMPATIBLE)
                .map(PluginDependencyResolver.Result::pluginId)
                .toList();

        auditService.record(actor, "WORKFLOW_IMPORT", "WORKFLOW", created.getId(), "OK",
                Map.of("name", String.valueOf(created.getName()), "sourceWorkflowId",
                        String.valueOf(pkg.getSourceWorkflowId()), "forms", formIdMapping.size(),
                        "missingPlugins", missing));
        log.info("Imported workflow '{}' as {} for {} ({} form(s) re-created)", created.getName(),
                created.getId(), actor, formIdMapping.size());

        List<String> warnings = new ArrayList<>();
        if (!missing.isEmpty()) {
            warnings.add("Install or update these plugins before running the workflow: "
                    + String.join(", ", missing) + ".");
        }
        return new ImportResult(true, created.getId(), created.getName(), missing, warnings);
    }

    // ---- decryption ----

    private WorkflowPackage decrypt(byte[] file, char[] password) {
        if (file == null || file.length == 0) {
            throw new PackageIntegrityException("No file was provided.");
        }
        if (file.length > maxFileBytes) {
            throw new IllegalArgumentException("The file is " + file.length + " bytes, above the "
                    + maxFileBytes + "-byte import limit.");
        }

        OrchPilotFile.Parsed parsed = OrchPilotFile.read(file);
        Map<String, Object> keyMeta = readKeyMeta(parsed.keyMeta());

        byte[] key;
        if (parsed.mode() == OrchPilotFile.MODE_PASSWORD) {
            if (password == null || password.length == 0) {
                throw new IllegalArgumentException("This package is password-protected; a password is "
                        + "required to import it.");
            }
            byte[] salt = Base64.getDecoder().decode(str(keyMeta, "salt"));
            key = crypto.deriveKey(password, salt, intValue(keyMeta, "iterations"),
                    intValue(keyMeta, "memoryKb"), intValue(keyMeta, "parallelism"));
        } else {
            if (masterKey == null) {
                throw new IllegalStateException("This is a platform-encrypted package, but this environment "
                        + "has no master key configured to open it.");
            }
            byte[] wrapped = Base64.getDecoder().decode(str(keyMeta, "wrappedKey"));
            byte[] wrapNonce = Base64.getDecoder().decode(str(keyMeta, "wrapNonce"));
            key = crypto.unwrapKey(masterKey, wrapNonce, wrapped);
        }

        try {
            byte[] plaintext = crypto.decrypt(key, parsed.nonce(), parsed.ciphertext());
            return codec.deserialize(plaintext);
        } finally {
            PackageCrypto.wipe(key);
        }
    }

    private Map<String, String> importForms(List<FormDefinition> forms) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (FormDefinition form : forms) {
            String oldId = form.getId();
            // Clearing the id makes create() mint a new one, so the import never reuses another tenant's form
            // id — the specification's rule for cross-tenant form imports.
            form.setId(null);
            FormDefinition created = formService.create(form);
            if (oldId != null) {
                mapping.put(oldId, created.getId());
            }
        }
        return mapping;
    }

    /** Converts a decrypted package into the request the create path understands. */
    private WorkflowRequest toRequest(WorkflowPackage pkg) {
        List<WorkflowRequest.NodeRequest> nodes = new ArrayList<>();
        for (WorkflowNode node : pkg.getNodes()) {
            nodes.add(new WorkflowRequest.NodeRequest(
                    node.getId(), node.getType(), node.getName(), node.getDescription(),
                    node.getPluginId(), node.getPluginVersion(), node.getConfiguration(),
                    node.getInputMapping(), node.getOutputMapping(), conditions(node), node.getDefaultBranch(),
                    node.getFormId(), node.getFormVersion(), node.isWaitForInput(), node.getOutputs(),
                    retry(node), node.getErrorPolicy() == null ? null : node.getErrorPolicy().name(),
                    node.getCompensationNodeId(), node.getTimeoutMillis(), node.getPresentation()));
        }
        List<WorkflowRequest.ConnectionRequest> connections = new ArrayList<>();
        for (WorkflowConnection connection : pkg.getConnections()) {
            connections.add(new WorkflowRequest.ConnectionRequest(connection.getId(), connection.getSource(),
                    connection.getSourcePort(), connection.getTarget(), connection.getLabel(),
                    connection.getCondition()));
        }
        return new WorkflowRequest(pkg.getName(), pkg.getDescription(), nodes, connections,
                pkg.getVariables(), triggers(pkg), pkg.getMetadata());
    }

    private List<WorkflowRequest.ConditionRequest> conditions(WorkflowNode node) {
        if (node.getConditions() == null) {
            return List.of();
        }
        List<WorkflowRequest.ConditionRequest> conditions = new ArrayList<>();
        node.getConditions().forEach(condition ->
                conditions.add(new WorkflowRequest.ConditionRequest(condition.getBranch(),
                        condition.getExpression(), condition.getDescription())));
        return conditions;
    }

    private WorkflowRequest.RetryRequest retry(WorkflowNode node) {
        if (node.getRetry() == null) {
            return null;
        }
        return new WorkflowRequest.RetryRequest(node.getRetry().isEnabled(), node.getRetry().getMaxAttempts(),
                node.getRetry().getBackoffMillis(), node.getRetry().getBackoffMultiplier(),
                node.getRetry().getMaxBackoffMillis());
    }

    private List<WorkflowRequest.TriggerRequest> triggers(WorkflowPackage pkg) {
        List<WorkflowRequest.TriggerRequest> triggers = new ArrayList<>();
        pkg.getTriggers().forEach(trigger ->
                triggers.add(new WorkflowRequest.TriggerRequest(trigger.getId(), trigger.getType().name(),
                        trigger.isEnabled(), trigger.getCron(), trigger.getTimezone(), trigger.getEventName(),
                        trigger.getDefaultInput(), trigger.getSchedule())));
        return triggers;
    }

    private Map<String, Object> readKeyMeta(byte[] bytes) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = keyMetaMapper.readValue(bytes, Map.class);
            return meta;
        } catch (Exception ex) {
            throw new PackageIntegrityException("The package's key metadata is malformed.", ex);
        }
    }

    private static String str(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        if (value == null) {
            throw new PackageIntegrityException("The package's key metadata is missing '" + key + "'.");
        }
        return String.valueOf(value);
    }

    private static int intValue(Map<String, Object> meta, String key) {
        Object value = meta.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(str(meta, key));
        } catch (NumberFormatException ex) {
            throw new PackageIntegrityException("The package's key metadata field '" + key
                    + "' is not a number.");
        }
    }
}
