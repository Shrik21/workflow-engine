package com.orchpilot.workflow.ai.tool;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The tools an AI Agent can be given — every installed plugin node type, for the designer's tool picker.
 *
 * <p>Listing is enough to configure an agent, so it takes the ordinary {@code AI_PROVIDER_VIEW} permission.
 * Selecting a tool grants nothing on its own: the tool only runs when the workflow does, with the running user's
 * plugin permissions, enforced by the plugin executor.
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI tools", description = "Plugin-backed tools available to an AI Agent")
public class AIToolController {

    private final AIToolRegistry registry;

    public AIToolController(AIToolRegistry registry) {
        this.registry = registry;
    }

    @PreAuthorize("hasAuthority('AI_PROVIDER_VIEW')")
    @GetMapping("/tools")
    @Operation(summary = "List plugin-backed tools an AI Agent can be configured with")
    public List<AIToolRegistry.ToolDescriptor> tools() {
        return registry.available();
    }
}
