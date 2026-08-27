package com.orchpilot.pluginserver.permission;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The permission catalogue.
 *
 * <p>Served from the enum rather than from a collection, so a role editor can only ever offer permissions that
 * correspond to a check this registry actually makes. A permission invented in a UI would grant nothing however
 * convincing it looked, and this endpoint is what stops one being invented.
 *
 * <p>A top-level class rather than nested inside the role controller: this codebase has already been bitten by
 * nesting Spring-managed types inside a container class, and a controller nobody registered is a 404 that takes
 * an afternoon to explain.
 */
@RestController
@RequestMapping("/api/permissions")
@SecurityRequirement(name = "bearer")
@Tag(name = "Permissions", description = "Every permission this registry implements")
public class PermissionController {

    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_READ')")
    @Operation(summary = "Every permission, grouped for a role editor")
    public List<PermissionView> list() {
        return PluginPermission.all().stream()
                .map(permission -> new PermissionView(permission.name(), permission.description(),
                        permission.group().name(), permission.group().label()))
                .toList();
    }

    /**
     * One permission.
     *
     * @param name        what a role stores and a check compares against
     * @param description what holding it allows, in words
     * @param group       which section of a role editor it belongs in
     * @param groupLabel  that section's heading
     */
    public record PermissionView(String name, String description, String group, String groupLabel) {
    }
}
