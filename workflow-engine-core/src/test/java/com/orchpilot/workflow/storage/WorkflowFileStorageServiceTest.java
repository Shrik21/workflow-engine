package com.orchpilot.workflow.storage;

import com.orchpilot.workflow.model.Workflow;
import com.orchpilot.workflow.model.WorkflowVersion;
import com.orchpilot.workflow.repository.WorkflowRepository;
import com.orchpilot.workflow.repository.WorkflowVersionRepository;
import com.orchpilot.workflow.storage.audit.StorageAuditService;
import com.orchpilot.workflow.storage.exception.FileStorageException;
import com.orchpilot.workflow.storage.model.FileStatus;
import com.orchpilot.workflow.storage.model.StorageType;
import com.orchpilot.workflow.storage.model.WorkflowFileReference;
import com.orchpilot.workflow.storage.model.WorkflowStorageSettings;
import com.orchpilot.workflow.storage.provider.FileStorageProviderRegistry;
import com.orchpilot.workflow.storage.provider.LocalFileStorageProvider;
import com.orchpilot.workflow.storage.repository.WorkflowFileRepository;
import com.orchpilot.workflow.storage.service.WorkflowFileStorageService;
import com.orchpilot.workflow.storage.service.WorkflowStorageSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The central service, with a real provider over a temporary directory and mocked repositories.
 *
 * <p>The provider is real rather than mocked because the behaviours worth asserting here — that a failed database
 * insert leaves no orphaned bytes, that a file lands in the right version's directory — are only observable if
 * something actually writes to a disk.
 */
class WorkflowFileStorageServiceTest {

    private static final String WORKFLOW_ID = "WF-123";

    @TempDir
    Path root;

    private WorkflowFileRepository fileRepository;
    private WorkflowRepository workflowRepository;
    private WorkflowVersionRepository versionRepository;
    private WorkflowStorageSettingsService settingsService;
    private WorkflowFileStorageService service;
    private WorkflowStorageSettings settings;

    @BeforeEach
    void setUp() throws IOException {
        fileRepository = mock(WorkflowFileRepository.class);
        workflowRepository = mock(WorkflowRepository.class);
        versionRepository = mock(WorkflowVersionRepository.class);
        settingsService = mock(WorkflowStorageSettingsService.class);

        settings = new WorkflowStorageSettings();
        settings.setStorageType(StorageType.LOCAL);
        settings.setBasePath(root.toRealPath().toString());
        settings.setEnabled(true);
        lenient().when(settingsService.require()).thenReturn(settings);

        FileStorageProviderRegistry registry =
                new FileStorageProviderRegistry(List.of(new LocalFileStorageProvider()));

        service = new WorkflowFileStorageService(fileRepository, workflowRepository, versionRepository,
                settingsService, registry, mock(StorageAuditService.class));

        // A workflow whose draft is version 3, with versions 1 and 2 already published.
        Workflow workflow = new Workflow();
        workflow.setId(WORKFLOW_ID);
        workflow.setVersion(3);
        lenient().when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
        lenient().when(versionRepository.findByWorkflowIdAndVersion(eq(WORKFLOW_ID), anyInt()))
                .thenReturn(Optional.empty());
        lenient().when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 1))
                .thenReturn(Optional.of(new WorkflowVersion()));
        lenient().when(versionRepository.findByWorkflowIdAndVersion(WORKFLOW_ID, 2))
                .thenReturn(Optional.of(new WorkflowVersion()));

        lenient().when(fileRepository.save(any(WorkflowFileReference.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------------ storing

    @Test
    @DisplayName("stores a file under the documented layout and records only a relative path")
    void storesUnderTheDocumentedLayout() {
        WorkflowFileReference reference = service.storeFile(WORKFLOW_ID, 2, upload("customer-data.xlsx", "data"));

        assertThat(reference.getRelativePath())
                .startsWith("workflows/WF-123/v2/files/")
                .endsWith("-customer-data.xlsx");
        // The requirement that makes the storage root movable and exports portable.
        assertThat(reference.getRelativePath()).doesNotContain(root.toString());
        assertThat(root.resolve(reference.getRelativePath())).exists();

        assertThat(reference.getWorkflowVersion()).isEqualTo(2);
        assertThat(reference.getStatus()).isEqualTo(FileStatus.ACTIVE);
        assertThat(reference.getSize()).isEqualTo(4);
        assertThat(reference.getChecksum()).isNotBlank().hasSize(64);
    }

    @Test
    @DisplayName("the stored filename carries the file id, so two identical uploads never collide")
    void identicalNamesDoNotCollide() {
        WorkflowFileReference first = service.storeFile(WORKFLOW_ID, 2, upload("invoice.pdf", "one"));
        WorkflowFileReference second = service.storeFile(WORKFLOW_ID, 2, upload("invoice.pdf", "two"));

        assertThat(first.getStoredFileName()).isNotEqualTo(second.getStoredFileName());
        assertThat(root.resolve(first.getRelativePath())).exists();
        assertThat(root.resolve(second.getRelativePath())).exists();
    }

    @Test
    @DisplayName("a hostile upload filename cannot escape the version directory")
    void hostileUploadNameCannotEscape() {
        WorkflowFileReference reference = service.storeFile(WORKFLOW_ID, 2, upload("../../secret.txt", "x"));

        assertThat(reference.getRelativePath()).startsWith("workflows/WF-123/v2/files/");
        assertThat(reference.getRelativePath()).doesNotContain("..");
        // The display name is sanitised too, because it is echoed in a Content-Disposition header.
        assertThat(reference.getOriginalFileName()).isEqualTo("secret.txt");
    }

    @Test
    @DisplayName("a version that does not exist is refused, naming the draft that does")
    void refusesAnUnknownVersion() {
        assertThatThrownBy(() -> service.storeFile(WORKFLOW_ID, 99, upload("a.txt", "x")))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("has no version 99")
                .hasMessageContaining("v3");
    }

    @Test
    @DisplayName("the current draft accepts uploads, so a file added while drafting belongs to the next publish")
    void acceptsTheCurrentDraftVersion() {
        // Version 3 has no WorkflowVersion document yet; it is the number the next publish will produce.
        WorkflowFileReference reference = service.storeFile(WORKFLOW_ID, 3, upload("draft.txt", "x"));

        assertThat(reference.getWorkflowVersion()).isEqualTo(3);
        assertThat(reference.getRelativePath()).startsWith("workflows/WF-123/v3/files/");
    }

    @Test
    @DisplayName("an empty upload is refused before anything is written")
    void refusesAnEmptyUpload() {
        assertThatThrownBy(() -> service.storeFile(WORKFLOW_ID, 2,
                new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0])))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("empty");
    }

    // ------------------------------------------------------------------ the consistency guarantee

    @Test
    @DisplayName("a failed database insert removes the bytes it had already written")
    void cleansUpWhenTheDatabaseInsertFails() throws IOException {
        when(fileRepository.save(any(WorkflowFileReference.class)))
                .thenThrow(new IllegalStateException("mongo is down"));

        assertThatThrownBy(() -> service.storeFile(WORKFLOW_ID, 2, upload("doomed.pdf", "payload")))
                .isInstanceOf(IllegalStateException.class);

        // The compensating action: no orphan left behind that nothing references and nothing can find.
        Path versionDirectory = root.resolve("workflows/WF-123/v2/files");
        if (Files.exists(versionDirectory)) {
            try (var entries = Files.list(versionDirectory)) {
                assertThat(entries).isEmpty();
            }
        }
    }

    // ------------------------------------------------------------------ isolation

    @Test
    @DisplayName("a file id from another workflow is not found, because the scope is part of the query")
    void aFileFromAnotherWorkflowIsNotFound() {
        // The repository is asked for (fileId, workflowId, version) together, so a foreign id simply misses.
        when(fileRepository.findByFileIdAndWorkflowIdAndWorkflowVersion("other-file", WORKFLOW_ID, 2))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMetadata(WORKFLOW_ID, 2, "other-file"))
                .isInstanceOf(FileStorageException.class)
                .satisfies(ex -> assertThat(((FileStorageException) ex).getErrorCode())
                        .isEqualTo("FILE_NOT_FOUND"));
    }

    @Test
    @DisplayName("a deleted file is not returned, even though its audit record survives")
    void aDeletedFileIsNotReturned() {
        WorkflowFileReference deleted = new WorkflowFileReference();
        deleted.setFileId("f1");
        deleted.setStatus(FileStatus.DELETED);
        when(fileRepository.findByFileIdAndWorkflowIdAndWorkflowVersion("f1", WORKFLOW_ID, 2))
                .thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> service.getMetadata(WORKFLOW_ID, 2, "f1"))
                .isInstanceOf(FileStorageException.class);
    }

    @Test
    @DisplayName("versions are isolated: the same filename in v1 and v2 are different files")
    void versionsAreIsolated() {
        WorkflowFileReference v1 = service.storeFile(WORKFLOW_ID, 1, upload("document.pdf", "version one"));
        WorkflowFileReference v2 = service.storeFile(WORKFLOW_ID, 2, upload("document.pdf", "version two"));

        assertThat(v1.getRelativePath()).contains("/v1/");
        assertThat(v2.getRelativePath()).contains("/v2/");
        assertThat(root.resolve(v1.getRelativePath())).exists();
        assertThat(root.resolve(v2.getRelativePath())).exists();
        // Publishing v2 must not have disturbed v1's content.
        assertThat(root.resolve(v1.getRelativePath())).hasContent("version one");
    }

    @Test
    @DisplayName("a tenant's files sit under their own prefix")
    void tenantFilesAreIsolated() {
        Workflow tenantWorkflow = new Workflow();
        tenantWorkflow.setId("WF-T");
        tenantWorkflow.setVersion(1);
        tenantWorkflow.setTenantId("tenant123");
        when(workflowRepository.findById("WF-T")).thenReturn(Optional.of(tenantWorkflow));

        WorkflowFileReference reference = service.storeFile("WF-T", 1, upload("a.pdf", "x"));

        assertThat(reference.getRelativePath()).startsWith("tenants/tenant123/workflows/WF-T/v1/files/");
        assertThat(reference.getTenantId()).isEqualTo("tenant123");
    }

    // ------------------------------------------------------------------ not configured

    @Test
    @DisplayName("with no storage configured, an upload is refused with the documented code and message")
    void refusesWhenStorageIsNotConfigured() {
        when(settingsService.require()).thenThrow(FileStorageException.notConfigured());

        assertThatThrownBy(() -> service.storeFile(WORKFLOW_ID, 2, upload("a.txt", "x")))
                .isInstanceOf(FileStorageException.class)
                .satisfies(ex -> {
                    FileStorageException failure = (FileStorageException) ex;
                    assertThat(failure.getErrorCode()).isEqualTo("FILE_STORAGE_NOT_CONFIGURED");
                    // The exact wording the specification asks the user to see.
                    assertThat(failure.getMessage())
                            .isEqualTo("Workflow file storage has not been configured. "
                                    + "Please contact an administrator.");
                });
    }

    // ------------------------------------------------------------------ cloning

    @Test
    @DisplayName("cloning copies the bytes to new references rather than sharing the originals")
    void cloningCopiesRatherThanShares() {
        WorkflowFileReference original = service.storeFile(WORKFLOW_ID, 2, upload("template.xlsx", "content"));
        when(fileRepository.findByWorkflowIdAndWorkflowVersionAndStatusOrderByCreatedAtDesc(
                WORKFLOW_ID, 2, FileStatus.ACTIVE)).thenReturn(List.of(original));

        Workflow clone = new Workflow();
        clone.setId("WF-200");
        clone.setVersion(1);
        when(workflowRepository.findById("WF-200")).thenReturn(Optional.of(clone));

        List<WorkflowFileReference> copies = service.copyVersionFiles(WORKFLOW_ID, 2, "WF-200", 1);

        assertThat(copies).hasSize(1);
        WorkflowFileReference copy = copies.get(0);
        // A new id under the clone's own path — deleting from the clone must not touch the original.
        assertThat(copy.getFileId()).isNotEqualTo(original.getFileId());
        assertThat(copy.getRelativePath()).startsWith("workflows/WF-200/v1/files/");
        // Same bytes, so the checksum must match.
        assertThat(copy.getChecksum()).isEqualTo(original.getChecksum());
        assertThat(root.resolve(original.getRelativePath())).exists();
    }

    // ------------------------------------------------------------------ consistency check

    @Test
    @DisplayName("the consistency check finds a reference whose content has gone missing")
    void consistencyCheckFindsMissingContent() throws IOException {
        WorkflowFileReference reference = service.storeFile(WORKFLOW_ID, 2, upload("gone.pdf", "x"));
        when(fileRepository.findByWorkflowIdAndStatus(WORKFLOW_ID, FileStatus.ACTIVE))
                .thenReturn(List.of(reference));

        // Something removed the file behind the application's back.
        Files.delete(root.resolve(reference.getRelativePath()));

        var report = service.checkConsistency(WORKFLOW_ID);

        assertThat(report.missingFromStorage()).containsExactly(reference.getFileId());
        assertThat(report.orphanedInStorage()).isEmpty();
    }

    @Test
    @DisplayName("the consistency check finds stored bytes that no reference claims")
    void consistencyCheckFindsOrphans() throws IOException {
        WorkflowFileReference reference = service.storeFile(WORKFLOW_ID, 2, upload("kept.pdf", "x"));
        when(fileRepository.findByWorkflowIdAndStatus(WORKFLOW_ID, FileStatus.ACTIVE))
                .thenReturn(List.of(reference));

        Files.createFile(root.resolve("workflows/WF-123/v2/files").resolve("stray-file.bin"));

        var report = service.checkConsistency(WORKFLOW_ID);

        assertThat(report.missingFromStorage()).isEmpty();
        assertThat(report.orphanedInStorage())
                .containsExactly("workflows/WF-123/v2/files/stray-file.bin");
    }

    // ------------------------------------------------------------------ helpers

    private static MockMultipartFile upload(String fileName, String content) {
        return new MockMultipartFile("file", fileName, "application/octet-stream",
                content.getBytes(StandardCharsets.UTF_8));
    }
}
