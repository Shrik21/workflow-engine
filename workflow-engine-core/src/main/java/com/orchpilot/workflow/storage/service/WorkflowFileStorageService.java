package com.orchpilot.workflow.storage.service;

import com.orchpilot.workflow.auth.security.CurrentUser;
import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.repository.WorkflowRepository;
import com.orchpilot.workflow.repository.WorkflowVersionRepository;
import com.orchpilot.workflow.storage.audit.StorageAuditService;
import com.orchpilot.workflow.storage.dto.WorkflowFileDtos.ConsistencyReport;
import com.orchpilot.workflow.storage.exception.FileStorageException;
import com.orchpilot.workflow.storage.model.FileStatus;
import com.orchpilot.workflow.storage.model.StorageType;
import com.orchpilot.workflow.storage.model.WorkflowFileReference;
import com.orchpilot.workflow.storage.model.WorkflowStorageSettings;
import com.orchpilot.workflow.storage.provider.FileStorageProvider;
import com.orchpilot.workflow.storage.provider.FileStorageProviderRegistry;
import com.orchpilot.workflow.storage.provider.StoredObject;
import com.orchpilot.workflow.storage.repository.WorkflowFileRepository;
import com.orchpilot.workflow.storage.util.FilenameSanitizer;
import com.orchpilot.workflow.storage.util.StoragePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The one way anything in the platform reaches a workflow file.
 *
 * <h2>Why everything funnels through here</h2>
 *
 * Controllers, node executors and plugins all need files, and each of them writing its own path arithmetic is
 * how a traversal bug eventually ships. This class owns the whole chain — settings lookup, version resolution,
 * filename sanitising, path construction, containment, checksum, the database record and the audit entry — so
 * there is exactly one implementation of each to review, and callers hold only ids.
 *
 * <h2>The consistency problem, stated honestly</h2>
 *
 * A file lives in two places: bytes on storage and a row in MongoDB. There is no transaction spanning both, so
 * one of four things can happen and each needs a deliberate answer:
 *
 * <ul>
 *   <li><strong>Both succeed</strong> — the normal case.</li>
 *   <li><strong>Write fails</strong> — nothing is recorded. Clean.</li>
 *   <li><strong>Write succeeds, insert fails</strong> — the orphaned bytes are deleted before the error is
 *       rethrown ({@link #store}). Compensating action, because leaving them would accumulate files nothing
 *       references and nothing can find.</li>
 *   <li><strong>Record exists, bytes gone</strong> — cannot be prevented, only detected: reported as
 *       {@code FILE_NOT_FOUND_IN_STORAGE} on access, and found in bulk by {@link #checkConsistency}.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 *
 * Stateless and safe for concurrent uploads. Two uploads of the same filename cannot collide because the stored
 * name carries a generated id, and the provider writes to a temporary file before an atomic move, so a reader
 * never sees a partial file.
 */
@Service
public class WorkflowFileStorageService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowFileStorageService.class);

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final WorkflowFileRepository fileRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowVersionRepository versionRepository;
    private final WorkflowStorageSettingsService settingsService;
    private final FileStorageProviderRegistry providers;
    private final StorageAuditService audit;

    public WorkflowFileStorageService(WorkflowFileRepository fileRepository,
                                      WorkflowRepository workflowRepository,
                                      WorkflowVersionRepository versionRepository,
                                      WorkflowStorageSettingsService settingsService,
                                      FileStorageProviderRegistry providers,
                                      StorageAuditService audit) {
        this.fileRepository = fileRepository;
        this.workflowRepository = workflowRepository;
        this.versionRepository = versionRepository;
        this.settingsService = settingsService;
        this.providers = providers;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ store

    /**
     * Stores an uploaded file against a workflow version.
     *
     * @param workflowId      the target workflow; must exist
     * @param workflowVersion the target version; must be published or the current draft
     * @param file            the upload
     * @return the reference, which is all the caller ever needs afterwards
     */
    public WorkflowFileReference storeFile(String workflowId, int workflowVersion, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw FileStorageException.rejected("The uploaded file is empty.");
        }
        WorkflowStorageSettings settings = settingsService.require();
        Workflow workflow = requireWorkflow(workflowId);
        requireVersionExists(workflow, workflowVersion);

        try (InputStream content = file.getInputStream()) {
            return store(settings, workflow, workflowVersion, file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), content);
        } catch (IOException ex) {
            throw FileStorageException.ioFailure("read of the upload", ex);
        }
    }

    /**
     * Stores content from a stream, resolving the settings and the workflow itself.
     *
     * <p>The form a caller outside this package should use: it keeps {@link WorkflowStorageSettings} — an
     * object carrying the absolute storage root — from having to be passed around by callers that have no
     * business holding one. That matters most for the plugin file accessor, which must never see a path.
     *
     * @param declaredSize size hint, or -1; the recorded size is always the measured one
     * @return the new file's reference
     */
    public WorkflowFileReference storeStream(String workflowId, int workflowVersion, String originalFileName,
                                             String contentType, long declaredSize, InputStream content) {
        WorkflowStorageSettings settings = settingsService.require();
        Workflow workflow = requireWorkflow(workflowId);
        requireVersionExists(workflow, workflowVersion);
        return store(settings, workflow, workflowVersion, originalFileName, contentType, declaredSize, content);
    }

    /**
     * Stores content from a stream. The general form behind {@link #storeFile}.
     *
     * <p>Exposed separately so that an import package, a clone, or a node producing output can store a file
     * without first materialising it as a {@link MultipartFile}.
     *
     * @param declaredSize size hint, or -1; the recorded size is always the measured one
     */
    public WorkflowFileReference store(WorkflowStorageSettings settings, Workflow workflow, int workflowVersion,
                                       String originalFileName, String contentType, long declaredSize,
                                       InputStream content) {
        String tenantId = workflow.getTenantId();
        String fileId = newFileId();
        String storedFileName = StoragePaths.storedFileName(fileId, originalFileName);
        String relativePath = StoragePaths.filePath(tenantId, workflow.getId(), workflowVersion, storedFileName);

        FileStorageProvider provider = providers.require(settings.getStorageType());
        StoredObject stored = provider.store(settings.getBasePath(), relativePath, content, declaredSize);

        WorkflowFileReference reference = new WorkflowFileReference();
        reference.setFileId(fileId);
        reference.setTenantId(tenantId);
        reference.setWorkflowId(workflow.getId());
        reference.setWorkflowVersion(workflowVersion);
        reference.setOriginalFileName(displayName(originalFileName));
        reference.setStoredFileName(storedFileName);
        reference.setRelativePath(relativePath);
        reference.setContentType(contentType == null || contentType.isBlank()
                ? DEFAULT_CONTENT_TYPE : contentType);
        reference.setSize(stored.size());
        reference.setChecksum(stored.checksum());
        reference.setStorageType(settings.getStorageType());
        reference.setStatus(FileStatus.ACTIVE);
        reference.setCreatedAt(Instant.now());
        reference.setCreatedBy(CurrentUser.userId().orElse("system"));

        try {
            WorkflowFileReference saved = fileRepository.save(reference);
            audit.uploaded(saved.getCreatedBy(), saved);
            log.info("Stored file {} for workflow {} v{} ({} bytes)", fileId, workflow.getId(),
                    workflowVersion, stored.size());
            return saved;
        } catch (RuntimeException ex) {
            // The bytes are already on storage and nothing references them. Remove them, then report the real
            // failure — an orphan nobody can find is worse than the error the caller was going to get anyway.
            log.error("Database insert failed after writing file {}; removing the orphaned content", fileId, ex);
            try {
                provider.delete(settings.getBasePath(), relativePath);
            } catch (RuntimeException cleanupFailure) {
                log.error("Could not remove orphaned content for file {}. It must be cleaned up manually.",
                        fileId, cleanupFailure);
            }
            throw ex;
        }
    }

    // ------------------------------------------------------------------ read

    /**
     * Looks a file up within its workflow and version.
     *
     * <p>The scope is part of the query rather than a check afterwards, so a file id belonging to another
     * workflow simply does not match. That is what makes an insecure-direct-object-reference impossible here
     * rather than merely unlikely.
     */
    public WorkflowFileReference getMetadata(String workflowId, int workflowVersion, String fileId) {
        WorkflowFileReference reference = fileRepository
                .findByFileIdAndWorkflowIdAndWorkflowVersion(fileId, workflowId, workflowVersion)
                .orElseThrow(() -> FileStorageException.fileNotFound(fileId));
        if (reference.getStatus() == FileStatus.DELETED) {
            throw FileStorageException.fileNotFound(fileId);
        }
        return reference;
    }

    /**
     * Opens a file for streaming.
     *
     * @return a stream the caller must close
     * @throws FileStorageException when the reference exists but the bytes do not
     */
    public InputStream getFile(String workflowId, int workflowVersion, String fileId) {
        WorkflowFileReference reference = getMetadata(workflowId, workflowVersion, fileId);
        WorkflowStorageSettings settings = settingsService.require();
        FileStorageProvider provider = providers.require(reference.getStorageType());

        InputStream stream = provider.read(settings.getBasePath(), reference.getRelativePath());
        audit.downloaded(CurrentUser.userId().orElse("system"), reference);
        return stream;
    }

    /** @return the active files of one workflow version, newest first */
    public List<WorkflowFileReference> listFiles(String workflowId, int workflowVersion) {
        return fileRepository.findByWorkflowIdAndWorkflowVersionAndStatusOrderByCreatedAtDesc(
                workflowId, workflowVersion, FileStatus.ACTIVE);
    }

    /** @return whether the bytes are actually present, used to set {@code downloadAvailable} on a listing */
    public boolean exists(WorkflowFileReference reference) {
        try {
            WorkflowStorageSettings settings = settingsService.require();
            return providers.require(reference.getStorageType())
                    .exists(settings.getBasePath(), reference.getRelativePath());
        } catch (RuntimeException ex) {
            // Unconfigured or unreachable storage means "cannot download", not a failed listing.
            return false;
        }
    }

    // ------------------------------------------------------------------ delete

    /**
     * Deletes a file's content and marks its reference.
     *
     * <p>Content first, then the record. The other order would leave bytes behind with nothing pointing at them
     * if the second step failed; this order leaves, at worst, a reference marked {@code DELETED} whose content is
     * already gone — which is the state the reference claims anyway.
     */
    public void deleteFile(String workflowId, int workflowVersion, String fileId) {
        WorkflowFileReference reference = getMetadata(workflowId, workflowVersion, fileId);
        WorkflowStorageSettings settings = settingsService.require();

        providers.require(reference.getStorageType())
                .delete(settings.getBasePath(), reference.getRelativePath());

        String actor = CurrentUser.userId().orElse("system");
        reference.setStatus(FileStatus.DELETED);
        reference.setDeletedAt(Instant.now());
        reference.setDeletedBy(actor);
        fileRepository.save(reference);

        audit.deleted(actor, reference);
        log.info("Deleted file {} from workflow {} v{}", fileId, workflowId, workflowVersion);
    }

    // ------------------------------------------------------------------ version-level operations

    /**
     * Creates the directory for a version's files.
     *
     * <p>Optional in practice — {@link #store} creates parents as it writes — but useful at publish time so the
     * layout is visible to an operator before anything is uploaded.
     */
    public void createVersionStorage(String workflowId, int workflowVersion) {
        WorkflowStorageSettings settings = settingsService.require();
        Workflow workflow = requireWorkflow(workflowId);
        String directory = StoragePaths.versionDirectory(workflow.getTenantId(), workflowId, workflowVersion);
        // list() on a local provider is a no-op for a missing directory, so a probe here is enough to surface
        // an unusable root without creating anything a caller did not ask for.
        providers.require(settings.getStorageType()).list(settings.getBasePath(), directory);
    }

    /**
     * Removes a whole version's files.
     *
     * <p>Not called when a version is archived. Archiving must not destroy data — that is what the retention
     * policy is for, once something enforces it — so this exists for workflow deletion and for an administrator
     * acting deliberately.
     *
     * @return how many references were marked deleted
     */
    public int deleteVersionStorage(String workflowId, int workflowVersion) {
        WorkflowStorageSettings settings = settingsService.require();
        Workflow workflow = requireWorkflow(workflowId);
        String directory = StoragePaths.versionDirectory(workflow.getTenantId(), workflowId, workflowVersion);

        providers.require(settings.getStorageType()).deletePrefix(settings.getBasePath(), directory);

        String actor = CurrentUser.userId().orElse("system");
        List<WorkflowFileReference> references = fileRepository
                .findByWorkflowIdAndWorkflowVersionAndStatusOrderByCreatedAtDesc(
                        workflowId, workflowVersion, FileStatus.ACTIVE);
        Instant now = Instant.now();
        for (WorkflowFileReference reference : references) {
            reference.setStatus(FileStatus.DELETED);
            reference.setDeletedAt(now);
            reference.setDeletedBy(actor);
        }
        fileRepository.saveAll(references);
        log.info("Deleted {} files for workflow {} v{}", references.size(), workflowId, workflowVersion);
        return references.size();
    }

    /**
     * Copies one version's files to another workflow and version — the operation behind cloning.
     *
     * <p>Copies rather than re-points. A clone that shared the original's references would mean deleting a file
     * from the clone deletes it from the original, and the two workflows' lifecycles would be permanently
     * entangled. New workflow, new files, new ids.
     *
     * @return the new references
     */
    public List<WorkflowFileReference> copyVersionFiles(String sourceWorkflowId, int sourceVersion,
                                                        String targetWorkflowId, int targetVersion) {
        WorkflowStorageSettings settings = settingsService.require();
        Workflow target = requireWorkflow(targetWorkflowId);
        FileStorageProvider provider = providers.require(settings.getStorageType());

        List<WorkflowFileReference> sources = listFiles(sourceWorkflowId, sourceVersion);
        List<WorkflowFileReference> copies = new ArrayList<>(sources.size());

        for (WorkflowFileReference source : sources) {
            try (InputStream content = provider.read(settings.getBasePath(), source.getRelativePath())) {
                copies.add(store(settings, target, targetVersion, source.getOriginalFileName(),
                        source.getContentType(), source.getSize(), content));
            } catch (IOException ex) {
                throw FileStorageException.ioFailure("copy", ex);
            } catch (FileStorageException ex) {
                // One missing source file should not abandon the clone half-copied; record it and continue.
                log.warn("Skipped file {} while cloning workflow {} v{}: {}", source.getFileId(),
                        sourceWorkflowId, sourceVersion, ex.getMessage());
            }
        }
        log.info("Copied {} of {} files from workflow {} v{} to {} v{}", copies.size(), sources.size(),
                sourceWorkflowId, sourceVersion, targetWorkflowId, targetVersion);
        return copies;
    }

    // ------------------------------------------------------------------ consistency

    /**
     * Compares the database against storage, in both directions.
     *
     * <p>Reports only; it repairs nothing. Deciding whether a missing file should be restored from backup or its
     * reference retired is a judgement about the data, and a tool that guessed would eventually guess wrong on
     * something that mattered.
     */
    public ConsistencyReport checkConsistency(String workflowId) {
        WorkflowStorageSettings settings = settingsService.require();
        Workflow workflow = requireWorkflow(workflowId);
        FileStorageProvider provider = providers.require(settings.getStorageType());

        List<WorkflowFileReference> references = fileRepository.findByWorkflowIdAndStatus(
                workflowId, FileStatus.ACTIVE);

        List<String> missing = new ArrayList<>();
        Set<String> expectedKeys = new HashSet<>();
        Set<Integer> versions = new HashSet<>();

        for (WorkflowFileReference reference : references) {
            expectedKeys.add(reference.getRelativePath());
            versions.add(reference.getWorkflowVersion());
            if (!provider.exists(settings.getBasePath(), reference.getRelativePath())) {
                missing.add(reference.getFileId());
            }
        }

        List<String> orphaned = new ArrayList<>();
        for (Integer version : versions) {
            String directory = StoragePaths.versionDirectory(workflow.getTenantId(), workflowId, version);
            for (String key : provider.list(settings.getBasePath(), directory)) {
                if (!expectedKeys.contains(key)) {
                    orphaned.add(key);
                }
            }
        }

        if (!missing.isEmpty() || !orphaned.isEmpty()) {
            log.warn("Storage consistency check for workflow {}: {} references missing content, {} orphaned "
                    + "objects", workflowId, missing.size(), orphaned.size());
        }
        return new ConsistencyReport(workflowId, references.size(), missing, orphaned);
    }

    // ------------------------------------------------------------------ helpers

    private Workflow requireWorkflow(String workflowId) {
        return workflowRepository.findById(workflowId)
                .orElseThrow(() -> new com.orchpilot.workflow.exception.WorkflowNotFoundException(workflowId));
    }

    /**
     * Confirms the target version is one files may belong to.
     *
     * <p>Two are valid: a published version, and the current draft. {@code Workflow.version} is the number the
     * next publish will produce, so uploading against it means a file added while drafting belongs to the version
     * that publish creates — no file has to move when the version is cut. Anything beyond that is a caller
     * inventing a version number, which is refused rather than silently creating a directory for a version that
     * does not exist.
     */
    private void requireVersionExists(Workflow workflow, int workflowVersion) {
        if (workflowVersion < 1) {
            throw FileStorageException.rejected("Workflow version must be 1 or greater.");
        }
        if (workflowVersion == workflow.getVersion()) {
            return; // The working draft.
        }
        Optional<?> published = versionRepository.findByWorkflowIdAndVersion(workflow.getId(), workflowVersion);
        if (published.isEmpty()) {
            throw FileStorageException.rejected(
                    "Workflow " + workflow.getId() + " has no version " + workflowVersion
                            + ". Files can be attached to a published version or to the current draft (v"
                            + workflow.getVersion() + ").");
        }
    }

    /** A short, collision-resistant id that is also safe as a path segment. */
    private static String newFileId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * The name shown to users.
     *
     * <p>Sanitised even though it is never used to build a path: it is rendered in the console and echoed in a
     * {@code Content-Disposition} header, so a name carrying control characters or quotes is a problem there
     * instead. Storing the safe form means every consumer gets it right without having to know.
     */
    private static String displayName(String originalFileName) {
        return FilenameSanitizer.sanitize(originalFileName);
    }
}
