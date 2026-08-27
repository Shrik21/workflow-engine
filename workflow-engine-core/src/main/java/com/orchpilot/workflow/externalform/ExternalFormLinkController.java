package com.orchpilot.workflow.externalform;

import com.orchpilot.workflow.auth.security.AuthPrincipal;
import com.orchpilot.workflow.auth.security.CurrentUser;
import com.orchpilot.workflow.config.WorkflowEngineProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Internal management of a task's external form links: generate, revoke, regenerate, and view status.
 *
 * <h2>The raw token is returned exactly once</h2>
 *
 * Generate and regenerate return the full URL — the only time the raw token leaves the server — so the operator
 * can copy or send it. Every other response, including the status list, carries no token and no hash: once the
 * link exists, it is identified by its opaque token id, its status and its expiry, never by anything that could
 * reconstruct the URL.
 */
@RestController
@RequestMapping("/api/workflow-tasks/{taskId}/external-link")
@Tag(name = "External form links", description = "Generate, revoke and regenerate secure external form links")
public class ExternalFormLinkController {

    private final ExternalFormTokenService tokenService;
    private final WorkflowEngineProperties properties;

    public ExternalFormLinkController(ExternalFormTokenService tokenService,
                                      WorkflowEngineProperties properties) {
        this.tokenService = tokenService;
        this.properties = properties;
    }

    /** Options an operator may set when generating a link; all optional, sensible defaults applied. */
    public record GenerateRequest(Long expirationHours, Integer maxSubmissions, Boolean allowSubmit,
                                  Boolean allowDraft, String customerName, String customerEmail,
                                  String customerReference) {
    }

    /** The generated link — the one response that carries the URL. */
    public record LinkResponse(String url, String tokenId, String status, Instant expiresAt,
                               int maxSubmissions) {
    }

    /** A link's status, without anything that could reconstruct its URL. */
    public record LinkSummary(String tokenId, String status, Instant expiresAt, Instant createdAt,
                              String createdBy, int submissionCount, int maxSubmissions) {

        static LinkSummary of(ExternalFormAccessToken token) {
            return new LinkSummary(token.getId(), token.getStatus().name(), token.getExpiresAt(),
                    token.getCreatedAt(), token.getCreatedBy(), token.getSubmissionCount(),
                    token.getMaxSubmissions());
        }
    }

    @PreAuthorize("hasAuthority('EXTERNAL_FORM_CREATE_LINK')")
    @PostMapping
    @Operation(summary = "Generate a secure external form link for a task")
    public LinkResponse generate(@PathVariable String taskId,
                                 @RequestBody(required = false) GenerateRequest request) {
        ExternalFormTokenService.GeneratedLink link =
                tokenService.create(taskId, options(request), actor());
        return response(link);
    }

    @PreAuthorize("hasAuthority('EXTERNAL_FORM_REVOKE_LINK')")
    @PostMapping("/revoke")
    @Operation(summary = "Revoke a task's external form link",
            description = "The URL stops working immediately.")
    public List<LinkSummary> revoke(@PathVariable String taskId) {
        tokenService.revoke(taskId, actor());
        return summaries(taskId);
    }

    @PreAuthorize("hasAuthority('EXTERNAL_FORM_CREATE_LINK')")
    @PostMapping("/regenerate")
    @Operation(summary = "Revoke the current link and issue a fresh one",
            description = "The old URL is dead the moment the new one exists.")
    public LinkResponse regenerate(@PathVariable String taskId,
                                   @RequestBody(required = false) GenerateRequest request) {
        return response(tokenService.regenerate(taskId, options(request), actor()));
    }

    @PreAuthorize("hasAuthority('EXTERNAL_FORM_CREATE_LINK')")
    @GetMapping
    @Operation(summary = "The external links minted for a task, newest first")
    public List<LinkSummary> list(@PathVariable String taskId) {
        return summaries(taskId);
    }

    private List<LinkSummary> summaries(String taskId) {
        return tokenService.forTask(taskId).stream().map(LinkSummary::of).toList();
    }

    private ExternalFormTokenService.CreateOptions options(GenerateRequest request) {
        long hours = request != null && request.expirationHours() != null && request.expirationHours() > 0
                ? request.expirationHours() : properties.getExternalForm().getDefaultExpirationHours();
        int maxSubmissions = request != null && request.maxSubmissions() != null
                ? Math.max(1, request.maxSubmissions()) : 1;
        boolean allowSubmit = request == null || request.allowSubmit() == null || request.allowSubmit();
        boolean allowDraft = request == null || request.allowDraft() == null || request.allowDraft();
        return new ExternalFormTokenService.CreateOptions(Duration.ofHours(hours), maxSubmissions, allowSubmit,
                allowDraft, request == null ? null : request.customerName(),
                request == null ? null : request.customerEmail(),
                request == null ? null : request.customerReference());
    }

    private LinkResponse response(ExternalFormTokenService.GeneratedLink link) {
        String url = properties.getExternalForm().getBaseUrl() + link.rawToken();
        return new LinkResponse(url, link.token().getId(), link.token().getStatus().name(),
                link.token().getExpiresAt(), link.token().getMaxSubmissions());
    }

    private String actor() {
        AuthPrincipal principal = CurrentUser.principal().orElseThrow(() ->
                new AccessDeniedException("This endpoint requires an authenticated user"));
        return principal.getUsername();
    }
}
