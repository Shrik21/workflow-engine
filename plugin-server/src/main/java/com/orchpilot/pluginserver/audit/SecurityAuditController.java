package com.orchpilot.pluginserver.audit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The security trail.
 *
 * <h2>Who can read it, and why it is not administrators only</h2>
 *
 * Guarded by {@code PLUGIN_AUDIT_READ} rather than by the administrator role, so an auditor can be given sight
 * of what happened without also being given the ability to change any of it. Separating those is most of the
 * value of having an audit trail at all.
 *
 * <h2>Filters are the feature</h2>
 *
 * The interesting question is never "what happened" but "what failed, for this account, in this window". The
 * filters exist for that; the unfiltered list is mostly a starting point.
 */
@RestController
@RequestMapping("/api/security/audit")
@SecurityRequirement(name = "bearer")
@Tag(name = "Security audit", description = "Sign-ins, token activity and account changes")
public class SecurityAuditController {

    /** A page nobody asked to size. Large enough to scan, small enough not to ship a year of rows. */
    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 200;

    private final SecurityAuditRepository repository;

    public SecurityAuditController(SecurityAuditRepository repository) {
        this.repository = repository;
    }

    /**
     * The trail, newest first.
     *
     * @param username narrow to one account
     * @param action   narrow to one kind of event
     * @param success  narrow to successes or failures
     * @param page     page number
     * @param size     page size, capped
     * @return the matching entries
     */
    @GetMapping
    @PreAuthorize("hasAuthority('PLUGIN_AUDIT_READ')")
    @Operation(summary = "Read the security trail",
            description = "Newest first. Filters combine one at a time: a username, an action, or an "
                    + "outcome.")
    public Page<SecurityAuditLog> list(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) SecurityAuditLog.Action action,
            @RequestParam(required = false) Boolean success,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));

        if (username != null && !username.isBlank()) {
            return repository.findByUsernameIgnoreCaseOrderByTimestampDesc(username.trim(), pageable);
        }
        if (action != null) {
            return repository.findByActionOrderByTimestampDesc(action, pageable);
        }
        if (success != null) {
            return repository.findBySuccessOrderByTimestampDesc(success, pageable);
        }
        return repository.findAllByOrderByTimestampDesc(pageable);
    }

    /**
     * @return every action name, so a filter can offer them without hardcoding a list that drifts
     */
    @GetMapping("/actions")
    @PreAuthorize("hasAuthority('PLUGIN_AUDIT_READ')")
    @Operation(summary = "The actions that can appear in the trail")
    public SecurityAuditLog.Action[] actions() {
        return SecurityAuditLog.Action.values();
    }
}
