package com.orchpilot.workflow.ai.connection;

import com.orchpilot.workflow.ai.AIProviderFactory;
import com.orchpilot.workflow.ai.AIProviderType;
import com.orchpilot.workflow.ai.model.AIModel;
import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI providers, their models, and the connections that hold credentials.
 *
 * <p>Two permission tiers, matching the specification: <em>viewing</em> providers, models and connection
 * metadata is open to anyone who configures an AI Agent node ({@code AI_PROVIDER_VIEW}); <em>managing</em>
 * credentials — create, edit, test, delete — needs {@code AI_PROVIDER_MANAGE}. No endpoint here ever returns a
 * key: connection views carry only whether a key is set.
 */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "AI providers", description = "AI provider connections, models and capabilities")
public class AIProviderController {

    private final AIProviderConnectionService service;
    private final AIProviderFactory factory;

    public AIProviderController(AIProviderConnectionService service, AIProviderFactory factory) {
        this.service = service;
        this.factory = factory;
    }

    /** A provider type and what it can do, for the designer's provider dropdown. */
    public record ProviderView(String type, boolean supportsTools, boolean supportsStreaming,
                               boolean supportsVision, boolean supportsStructuredOutput) {
    }

    /** A connection without its credential — {@code hasKey} says only whether one is set. */
    public record ConnectionView(String id, String name, String providerType, String endpoint, boolean enabled,
                                 boolean hasKey, Map<String, Object> settings, Instant createdAt,
                                 Instant updatedAt) {

        static ConnectionView of(AIProviderConnection c) {
            return new ConnectionView(c.getId(), c.getName(), String.valueOf(c.getProviderType()),
                    c.getEndpoint(), c.isEnabled(), c.getSecretName() != null, c.getSettings(),
                    c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    /** The create/update body; {@code apiKey} is write-only and omitted on update keeps the existing key. */
    public record ConnectionRequestBody(String name, String providerType, String endpoint, String apiKey,
                                        Map<String, Object> settings, Boolean enabled) {
    }

    @PreAuthorize("hasAuthority('AI_PROVIDER_VIEW')")
    @GetMapping("/providers")
    @Operation(summary = "List installed AI providers and their capabilities")
    public List<ProviderView> providers() {
        List<ProviderView> views = new ArrayList<>();
        for (AIProviderType type : AIProviderType.values()) {
            if (!factory.supports(type)) {
                continue;
            }
            var provider = factory.forType(type);
            views.add(new ProviderView(type.name(), provider.supportsToolCalling(),
                    provider.supportsStreaming(), provider.supportsVision(),
                    provider.supportsStructuredOutput()));
        }
        return views;
    }

    @PreAuthorize("hasAuthority('AI_PROVIDER_VIEW')")
    @GetMapping("/connections")
    @Operation(summary = "List AI provider connections (never their keys)")
    public List<ConnectionView> connections() {
        return service.list().stream().map(ConnectionView::of).toList();
    }

    @PreAuthorize("hasAuthority('AI_PROVIDER_VIEW')")
    @GetMapping("/connections/{id}/models")
    @Operation(summary = "List the models a connection's provider offers",
            description = "Discovered live where the provider allows it (e.g. Ollama), otherwise a curated "
                    + "fallback. Never a hardcoded master list.")
    public List<AIModel> models(@PathVariable String id) {
        return service.models(id);
    }

    @PreAuthorize("hasAuthority('AI_PROVIDER_MANAGE')")
    @PostMapping("/connections")
    @Operation(summary = "Create an AI provider connection")
    public ConnectionView create(@RequestBody ConnectionRequestBody body) {
        return ConnectionView.of(service.create(toRequest(body), actor()));
    }

    @PreAuthorize("hasAuthority('AI_PROVIDER_MANAGE')")
    @PutMapping("/connections/{id}")
    @Operation(summary = "Update an AI provider connection",
            description = "Leaving the API key blank keeps the existing credential rather than clearing it.")
    public ConnectionView update(@PathVariable String id, @RequestBody ConnectionRequestBody body) {
        return ConnectionView.of(service.update(id, toRequest(body), actor()));
    }

    @PreAuthorize("hasAuthority('AI_PROVIDER_MANAGE')")
    @DeleteMapping("/connections/{id}")
    @Operation(summary = "Delete an AI provider connection and its stored key")
    public void delete(@PathVariable String id) {
        service.delete(id, actor());
    }

    @PreAuthorize("hasAuthority('AI_PROVIDER_MANAGE')")
    @PostMapping("/connections/{id}/test")
    @Operation(summary = "Test a connection by making a minimal call to its provider")
    public Map<String, Object> test(@PathVariable String id) {
        boolean ok = service.test(id);
        return Map.of("connected", ok);
    }

    private String actor() {
        AuthPrincipal principal = CurrentUser.principal().orElseThrow(() ->
                new AccessDeniedException("This endpoint requires an authenticated user"));
        return principal.getUsername();
    }

    private static AIProviderConnectionService.ConnectionRequest toRequest(ConnectionRequestBody body) {
        AIProviderType type = body.providerType() == null ? null
                : AIProviderType.valueOf(body.providerType().trim().toUpperCase(java.util.Locale.ROOT));
        return new AIProviderConnectionService.ConnectionRequest(body.name(), type, body.endpoint(),
                body.apiKey(), body.settings(), body.enabled());
    }
}
