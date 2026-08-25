package com.orchpilot.workflow.ai.cli;

import com.orchpilot.workflow.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Configuration management: tenant scoping, the single-default rule, and what reaches the audit trail.
 */
class AiCliConfigurationServiceTest {

    private static final String TENANT = "tenant-a";
    private static final String OTHER_TENANT = "tenant-b";
    private static final String PATH = "/usr/local/bin/claude";

    private AiCliConfigurationRepository repository;
    private AuditService audit;
    private AiCliConfigurationService service;
    private final List<AiCliConfiguration> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(AiCliConfigurationRepository.class);
        audit = mock(AuditService.class);
        AiCliProperties properties = new AiCliProperties();

        service = new AiCliConfigurationService(repository,
                new ExecutablePathValidator(properties),
                new AiCliProviderRegistry(List.of(new ClaudeCliProvider())),
                audit);

        stored.clear();
        when(repository.save(any())).thenAnswer(invocation -> {
            AiCliConfiguration configuration = invocation.getArgument(0);
            stored.removeIf(c -> c.getId().equals(configuration.getId()));
            stored.add(configuration);
            return configuration;
        });
        when(repository.findByTenantIdAndName(anyString(), anyString())).thenReturn(Optional.empty());
        when(repository.findByTenantIdAndDefaultConfigurationIsTrue(anyString())).thenReturn(List.of());
    }

    private static AiCliConfigurationService.ConfigurationRequest request(String name, Boolean isDefault) {
        return new AiCliConfigurationService.ConfigurationRequest(name, AiCliType.CLAUDE_CLI,
                OperatingSystemType.LINUX, PATH, true, isDefault, null);
    }

    // ------------------------------------------------------------------ creation

    @Test
    @DisplayName("creates a configuration and stamps it with the caller's tenant")
    void creates() {
        AiCliConfiguration created = service.create(request("Ubuntu Server", false), TENANT, "dev");

        assertThat(created.getTenantId()).isEqualTo(TENANT);
        assertThat(created.getName()).isEqualTo("Ubuntu Server");
        assertThat(created.getExecutablePath()).isEqualTo(PATH);
        // Never CONNECTED until something has actually been run.
        assertThat(created.getStatus()).isEqualTo(AiCliStatus.NOT_CONFIGURED);
    }

    @Test
    @DisplayName("refuses a path that fails validation, before anything is stored")
    void refusesBadPath() {
        var bad = new AiCliConfigurationService.ConfigurationRequest("X", AiCliType.CLAUDE_CLI,
                OperatingSystemType.LINUX, "/bin/bash", true, false, null);

        assertThatThrownBy(() -> service.create(bad, TENANT, "dev"))
                .isInstanceOf(AiCliException.class);
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("refuses a duplicate name within a tenant")
    void refusesDuplicateName() {
        when(repository.findByTenantIdAndName(TENANT, "Ubuntu Server"))
                .thenReturn(Optional.of(new AiCliConfiguration()));

        assertThatThrownBy(() -> service.create(request("Ubuntu Server", false), TENANT, "dev"))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode()).isEqualTo("AI_CLI_NAME_TAKEN"));
    }

    @Test
    @DisplayName("refuses a CLI type with no adapter installed")
    void refusesUnknownType() {
        var unknown = new AiCliConfigurationService.ConfigurationRequest("X", "GEMINI_CLI",
                OperatingSystemType.LINUX, PATH, true, false, null);

        assertThatThrownBy(() -> service.create(unknown, TENANT, "dev"))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_CLI_TYPE_NOT_SUPPORTED"));
    }

    // ------------------------------------------------------------------ tenant scoping

    @Test
    @DisplayName("will not return another tenant's configuration")
    void refusesCrossTenantRead() {
        AiCliConfiguration theirs = new AiCliConfiguration();
        theirs.setId("cfg-1");
        theirs.setTenantId(OTHER_TENANT);
        when(repository.findById("cfg-1")).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.get("cfg-1", TENANT))
                .isInstanceOf(AiCliException.class)
                // Same message as "does not exist", so an id cannot be probed for existence.
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode())
                        .isEqualTo("AI_CLI_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("a single-tenant deployment, where both tenants are null, still works")
    void tolerantOfNullTenant() {
        AiCliConfiguration configuration = new AiCliConfiguration();
        configuration.setId("cfg-1");
        configuration.setTenantId(null);
        when(repository.findById("cfg-1")).thenReturn(Optional.of(configuration));

        assertThat(service.get("cfg-1", null)).isSameAs(configuration);
    }

    // ------------------------------------------------------------------ the default

    @Test
    @DisplayName("promoting a default demotes the previous one")
    void onlyOneDefault() {
        AiCliConfiguration existing = new AiCliConfiguration();
        existing.setId("cfg-old");
        existing.setTenantId(TENANT);
        existing.setDefaultConfiguration(true);
        when(repository.findByTenantIdAndDefaultConfigurationIsTrue(TENANT)).thenReturn(List.of(existing));

        service.create(request("New default", true), TENANT, "dev");

        assertThat(existing.isDefaultConfiguration()).isFalse();
        verify(repository).saveAll(any());
    }

    @Test
    @DisplayName("falls back to the only enabled configuration when none is marked default")
    void singleEnabledIsTheDefault() {
        AiCliConfiguration only = new AiCliConfiguration();
        only.setTenantId(TENANT);
        when(repository.findByTenantIdAndTypeAndEnabledIsTrue(TENANT, AiCliType.CLAUDE_CLI))
                .thenReturn(List.of(only));

        assertThat(service.requireDefault(TENANT, AiCliType.CLAUDE_CLI)).isSameAs(only);
    }

    @Test
    @DisplayName("refuses to guess between several, and says what to do")
    void refusesToGuessDefault() {
        when(repository.findByTenantIdAndTypeAndEnabledIsTrue(TENANT, AiCliType.CLAUDE_CLI))
                .thenReturn(List.of(new AiCliConfiguration(), new AiCliConfiguration()));

        assertThatThrownBy(() -> service.requireDefault(TENANT, AiCliType.CLAUDE_CLI))
                .isInstanceOf(AiCliException.class)
                .satisfies(ex -> assertThat(((AiCliException) ex).errorCode()).isEqualTo("AI_CLI_NO_DEFAULT"))
                .hasMessageContaining("Mark one as default");
    }

    @Test
    @DisplayName("says where to configure one when there are none")
    void reportsNoConfiguration() {
        when(repository.findByTenantIdAndTypeAndEnabledIsTrue(TENANT, AiCliType.CLAUDE_CLI))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.requireDefault(TENANT, AiCliType.CLAUDE_CLI))
                .isInstanceOf(AiCliException.class)
                .hasMessageContaining("Settings");
    }

    // ------------------------------------------------------------------ updates

    @Test
    @DisplayName("changing the path clears a cached CONNECTED status")
    void changingPathClearsStatus() {
        AiCliConfiguration existing = new AiCliConfiguration();
        existing.setId("cfg-1");
        existing.setTenantId(TENANT);
        existing.setName("Server");
        existing.setType(AiCliType.CLAUDE_CLI);
        existing.setOperatingSystem(OperatingSystemType.LINUX);
        existing.setExecutablePath("/usr/bin/claude");
        existing.setStatus(AiCliStatus.CONNECTED);
        existing.setVersion("1.0.60");
        when(repository.findById("cfg-1")).thenReturn(Optional.of(existing));

        AiCliConfiguration updated = service.update("cfg-1",
                new AiCliConfigurationService.ConfigurationRequest("Server", AiCliType.CLAUDE_CLI,
                        OperatingSystemType.LINUX, "/usr/local/bin/claude", true, false, null),
                TENANT, "dev");

        // The old status described a different binary; keeping it would claim a check that never happened.
        assertThat(updated.getStatus()).isEqualTo(AiCliStatus.NOT_CONFIGURED);
        assertThat(updated.getVersion()).isNull();
    }

    // ------------------------------------------------------------------ audit

    @Test
    @DisplayName("records which executable the engine was pointed at")
    void auditsThePath() {
        service.create(request("Ubuntu Server", false), TENANT, "dev");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq("dev"), eq("AI_CLI_CONFIG_CREATED"), eq("AI_CLI_CONFIGURATION"),
                anyString(), eq("OK"), details.capture());

        // The single most security-relevant fact about this feature.
        assertThat(details.getValue()).containsEntry("executablePath", PATH);
        assertThat(details.getValue()).containsEntry("tenantId", TENANT);
    }
}
