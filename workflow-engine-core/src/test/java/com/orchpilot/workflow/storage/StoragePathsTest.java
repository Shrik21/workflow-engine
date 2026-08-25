package com.orchpilot.workflow.storage;

import com.orchpilot.workflow.storage.util.StoragePaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The relative-path layout, and the isolation guarantees that follow from it.
 *
 * <p>Workflow, version and tenant isolation are all properties of the path prefix, so they are asserted here
 * rather than through the filesystem: if two coordinates cannot produce overlapping prefixes, no amount of
 * concurrent writing can make their files collide.
 */
class StoragePathsTest {

    @Test
    @DisplayName("the single-tenant layout matches the documented structure")
    void singleTenantLayout() {
        String path = StoragePaths.filePath(null, "WF-123", 3, "abc123-customer-data.xlsx");

        assertThat(path).isEqualTo("workflows/WF-123/v3/files/abc123-customer-data.xlsx");
    }

    @Test
    @DisplayName("a tenant adds a prefix and nothing else changes")
    void multiTenantLayout() {
        String path = StoragePaths.filePath("tenant123", "workflow001", 1, "f1-a.pdf");

        assertThat(path).isEqualTo("tenants/tenant123/workflows/workflow001/v1/files/f1-a.pdf");
    }

    @Test
    @DisplayName("one tenant's tree cannot overlap another's")
    void tenantsAreIsolated() {
        String first = StoragePaths.versionDirectory("tenant-a", "WF-1", 1);
        String second = StoragePaths.versionDirectory("tenant-b", "WF-1", 1);

        assertThat(first).isNotEqualTo(second);
        assertThat(first).doesNotStartWith(second);
        assertThat(second).doesNotStartWith(first);
    }

    @Test
    @DisplayName("versions do not overlap, so publishing v2 cannot touch v1's files")
    void versionsAreIsolated() {
        String v1 = StoragePaths.versionDirectory(null, "WF-100", 1);
        String v2 = StoragePaths.versionDirectory(null, "WF-100", 2);

        assertThat(v1).isEqualTo("workflows/WF-100/v1/files");
        assertThat(v2).isEqualTo("workflows/WF-100/v2/files");
        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    @DisplayName("workflows do not overlap")
    void workflowsAreIsolated() {
        assertThat(StoragePaths.versionDirectory(null, "WF-1", 1))
                .isNotEqualTo(StoragePaths.versionDirectory(null, "WF-2", 1));

        // "WF-1" is a string prefix of "WF-10"; the separator is what keeps their trees apart.
        assertThat(StoragePaths.versionDirectory(null, "WF-10", 1))
                .doesNotStartWith(StoragePaths.versionDirectory(null, "WF-1", 1));
    }

    @Test
    @DisplayName("paths use forward slashes on every platform, so they survive a move between operating systems")
    void alwaysPosixStyle() {
        String path = StoragePaths.filePath("t", "w", 2, "f-a.txt");

        assertThat(path).doesNotContain("\\");
        assertThat(path).doesNotContain(java.io.File.separator.equals("\\") ? "\\" : "\u0000");
    }

    @Test
    @DisplayName("the stored filename leads with the file id, so identical uploads cannot collide")
    void storedNameCarriesTheFileId() {
        String first = StoragePaths.storedFileName("aaaa1111", "invoice.pdf");
        String second = StoragePaths.storedFileName("bbbb2222", "invoice.pdf");

        assertThat(first).isEqualTo("aaaa1111-invoice.pdf");
        assertThat(second).isEqualTo("bbbb2222-invoice.pdf");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("a hostile original filename cannot escape through the stored name")
    void storedNameSanitisesTheOriginal() {
        String stored = StoragePaths.storedFileName("aaaa1111", "../../secret.txt");

        assertThat(stored).isEqualTo("aaaa1111-secret.txt");
        assertThat(stored).doesNotContain("/").doesNotContain("..");
    }

    @Test
    @DisplayName("an unsafe workflow id is rejected rather than cleaned")
    void rejectsUnsafeIdentifiers() {
        assertThatThrownBy(() -> StoragePaths.versionDirectory(null, "../etc", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoragePaths.versionDirectory("../other", "WF-1", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("version zero and below are rejected")
    void rejectsInvalidVersions() {
        assertThatThrownBy(() -> StoragePaths.versionDirectory(null, "WF-1", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoragePaths.versionDirectory(null, "WF-1", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/absolute/path",
            "C:/windows/path",
            "workflows\\WF-1\\v1\\files\\a.txt",
            "workflows/../../../etc/passwd",
            "workflows/./WF-1/v1/files/a.txt",
    })
    @DisplayName("a tampered stored path is rejected when read back")
    void rejectsTamperedStoredPaths(String tampered) {
        // Guards against a document edited directly in the database rather than through the application.
        assertThatThrownBy(() -> StoragePaths.requireRelative(tampered))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a well-formed stored path is accepted unchanged")
    void acceptsWellFormedPaths() {
        String path = "workflows/WF-123/v3/files/abc-customer.xlsx";

        assertThat(StoragePaths.requireRelative(path)).isEqualTo(path);
    }
}
