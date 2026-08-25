package com.orchpilot.pluginserver.service;

import com.orchpilot.pluginserver.model.PluginAuditEvent;
import com.orchpilot.pluginserver.repository.PluginAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Records what happened, and who did it.
 *
 * <p>Never throws. Failing to write a trail entry must not fail the operation it describes, because the
 * alternative is a registry that stops accepting uploads when its audit collection is unwritable. It must,
 * however, be loud: a lost entry is logged at error.
 */
@Service
public class PluginAuditService {

    private static final Logger log = LoggerFactory.getLogger(PluginAuditService.class);

    private final PluginAuditRepository repository;

    public PluginAuditService(PluginAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * @param pluginId the plugin acted on
     * @param version  the version acted on, or null for a plugin-level action
     * @param action   what happened
     * @param outcome  {@code OK}, {@code DENIED} or {@code FAILED}
     * @param details  structured context; never bytes and never a token
     */
    public void record(String pluginId, String version, PluginAuditEvent.Action action, String outcome,
                       Map<String, Object> details) {
        try {
            repository.save(PluginAuditEvent.of(pluginId, version, action, currentActor(), outcome, details));
        } catch (RuntimeException ex) {
            log.error("Could not record {} on {}:{}: {}", action, pluginId, version, ex.getMessage());
        }
    }

    public Page<PluginAuditEvent> history(String pluginId, Pageable pageable) {
        return pluginId == null || pluginId.isBlank()
                ? repository.findAllByOrderByAtDesc(pageable)
                : repository.findByPluginIdOrderByAtDesc(pluginId, pageable);
    }

    /**
     * Who is calling.
     *
     * <p>Read from the security context rather than accepted as a parameter, so no call site can attribute an
     * action to somebody else. For a service token the name is the client id, which is what makes "this version
     * was downloaded by the staging workflow service" answerable.
     *
     * @return the caller's name, or {@code system} when there is no authenticated caller
     */
    public static String currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "system";
        }
        String name = authentication.getName();
        return name == null || name.isBlank() ? "system" : name;
    }
}
