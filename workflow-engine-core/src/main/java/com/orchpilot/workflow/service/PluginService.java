package com.orchpilot.workflow.service;

import com.orchpilot.workflow.dto.PluginExecutionResponse;
import com.orchpilot.workflow.dto.PluginResponse;
import com.orchpilot.workflow.dto.PluginVersionResponse;
import com.orchpilot.workflow.exception.PluginNotFoundException;
import com.orchpilot.workflow.model.PluginMetadata;
import com.orchpilot.workflow.model.PluginVersion;
import com.orchpilot.workflow.plugin.PluginHandle;
import com.orchpilot.workflow.plugin.PluginRegistry;
import com.orchpilot.workflow.repository.PluginExecutionRepository;
import com.orchpilot.workflow.repository.PluginMetadataRepository;
import com.orchpilot.workflow.repository.PluginVersionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Read side of plugin administration.
 *
 * <p>Kept separate from {@code PluginManager}, which owns the lifecycle. This service joins the persisted metadata
 * with live runtime state, so a response distinguishes "installed and marked active" from "actually loaded in this
 * instance right now" and reports in-flight invocation counts. That distinction is what an operator needs when a
 * plugin is misbehaving, and it is invisible from the database alone.
 */
@Service
public class PluginService {

    private final PluginMetadataRepository metadataRepository;
    private final PluginVersionRepository versionRepository;
    private final PluginExecutionRepository executionRepository;
    private final PluginRegistry registry;

    public PluginService(PluginMetadataRepository metadataRepository, PluginVersionRepository versionRepository,
                         PluginExecutionRepository executionRepository, PluginRegistry registry) {
        this.metadataRepository = metadataRepository;
        this.versionRepository = versionRepository;
        this.executionRepository = executionRepository;
        this.registry = registry;
    }

    /**
     * @return every installed plugin with its versions and runtime state
     */
    public List<PluginResponse> list() {
        List<PluginResponse> responses = new ArrayList<>();
        for (PluginMetadata head : metadataRepository.findAll()) {
            responses.add(toResponse(head));
        }
        return responses;
    }

    /**
     * @param pluginId plugin id
     * @return the plugin with its versions
     * @throws PluginNotFoundException when it is not installed
     */
    public PluginResponse get(String pluginId) {
        PluginMetadata head = metadataRepository.findById(pluginId)
                .orElseThrow(() -> new PluginNotFoundException(pluginId));
        return toResponse(head);
    }

    /**
     * @param pluginId plugin id
     * @return its versions, most recently uploaded first
     * @throws PluginNotFoundException when the plugin has no versions
     */
    public List<PluginVersionResponse> versions(String pluginId) {
        List<PluginVersion> versions = versionRepository.findByPluginIdOrderByUploadedAtDesc(pluginId);
        if (versions.isEmpty()) {
            throw new PluginNotFoundException(pluginId);
        }
        return versions.stream().map(this::toVersionResponse).toList();
    }

    /**
     * @param pluginId plugin id
     * @param version  version, or {@code null} for every version
     * @param pageable paging
     * @return recorded invocations, most recent first
     */
    public Page<PluginExecutionResponse> executions(String pluginId, String version, Pageable pageable) {
        Page<com.orchpilot.workflow.model.PluginExecutionRecord> page = (version == null || version.isBlank())
                ? executionRepository.findByPluginIdOrderByStartTimeDesc(pluginId, pageable)
                : executionRepository.findByPluginIdAndPluginVersionOrderByStartTimeDesc(pluginId, version,
                        pageable);
        return page.map(PluginExecutionResponse::from);
    }

    /**
     * @param pluginId plugin id
     * @param version  version
     * @return the version with its runtime state
     * @throws PluginNotFoundException when it is not installed
     */
    public PluginVersionResponse version(String pluginId, String version) {
        PluginVersion found = versionRepository.findByPluginIdAndVersion(pluginId, version)
                .orElseThrow(() -> new PluginNotFoundException(pluginId, version));
        return toVersionResponse(found);
    }

    private PluginResponse toResponse(PluginMetadata head) {
        List<PluginVersion> versions = versionRepository.findByPluginIdOrderByUploadedAtDesc(head.getId());
        List<PluginVersionResponse> versionResponses = versions.stream().map(this::toVersionResponse).toList();
        return new PluginResponse(head.getId(), head.getName(), head.getDescription(), head.getPluginType(),
                String.valueOf(head.getStatus()), head.getLatestVersion(),
                // The registry is authoritative for the default version: it is the one that will actually serve
                // an unpinned node, and it can differ from the stored value after a failed load.
                registry.findDefault(head.getId()).map(PluginHandle::version).orElse(head.getDefaultVersion()),
                versionResponses, head.getCreatedAt(), head.getUpdatedAt());
    }

    private PluginVersionResponse toVersionResponse(PluginVersion version) {
        Optional<PluginHandle> handle = registry.find(version.getPluginId(), version.getVersion());
        return handle
                .map(loaded -> PluginVersionResponse.from(version, true, loaded.activeLeaseCount(),
                        loaded.totalInvocations(), loaded.failedInvocations()))
                .orElseGet(() -> PluginVersionResponse.from(version));
    }
}
