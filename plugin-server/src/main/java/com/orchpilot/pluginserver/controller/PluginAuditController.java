package com.orchpilot.pluginserver.controller;

import com.orchpilot.pluginserver.model.PluginAuditEvent;
import com.orchpilot.pluginserver.service.PluginAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Reading the registry's trail.
 *
 * <p>Mounted at {@code /api/plugin-audit} rather than under {@code /api/plugins} to keep it away from the
 * {@code /{pluginId}} template. A literal path segment does win over a variable one in Spring, so
 * {@code /api/plugins/audit} would have worked; it would also have meant that a plugin legitimately called
 * "audit" became unreachable, which is a trap to leave for somebody.
 *
 * <p>Requires {@code PLUGIN_ADMIN}. The trail says who uploaded what and which service downloaded it, which is
 * more than a browsing user needs and enough to map out the estate.
 */
@RestController
@RequestMapping("/api/plugin-audit")
@Tag(name = "Plugin audit", description = "Who did what to the registry")
public class PluginAuditController {

    private static final int MAX_PAGE_SIZE = 200;

    private final PluginAuditService audit;

    public PluginAuditController(PluginAuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PLUGIN_AUDIT_READ')")
    @Operation(summary = "Read the registry's audit trail, newest first",
            description = """
                    Records uploads, rejected uploads, every lifecycle change, deletions and downloads.

                    Downloads are included on purpose: for a service that distributes executable code, the \
                    difference between knowing a bad version existed and knowing which workflow services fetched \
                    it is the difference between a warning and a remediation plan.""")
    public Page<AuditEntry> list(@RequestParam(required = false) String pluginId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        return audit.history(pluginId, pageable).map(AuditEntry::from);
    }

    /**
     * One trail entry.
     *
     * @param pluginId the plugin
     * @param version  the version, or null for a plugin-level action
     * @param action   what happened
     * @param actor    username for a person, client id for a service
     * @param outcome  {@code OK}, {@code DENIED} or {@code FAILED}
     * @param at       when
     * @param details  structured context
     */
    public record AuditEntry(String pluginId, String version, String action, String actor, String outcome,
                             Instant at, Map<String, Object> details) {

        static AuditEntry from(PluginAuditEvent event) {
            return new AuditEntry(event.getPluginId(), event.getVersion(),
                    event.getAction() == null ? null : event.getAction().name(),
                    event.getActor(), event.getOutcome(), event.getAt(), event.getDetails());
        }
    }
}
