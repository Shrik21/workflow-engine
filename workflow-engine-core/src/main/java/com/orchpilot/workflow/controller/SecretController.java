package com.orchpilot.workflow.controller;

import com.orchpilot.workflow.dto.SecretRequest;
import com.orchpilot.workflow.service.SecretService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Credential management.
 *
 * <p>There is deliberately no endpoint that returns a secret value, and there never should be. Writes go in, metadata
 * comes out. The only path from a stored secret to a consumer is a plugin calling {@code secrets().require(name)},
 * which is scope-checked and audited.
 *
 * <p>Behind the administrative API key, like plugin installation.
 */
@RestController
@RequestMapping("/api/secrets")
@Tag(name = "Secrets", description = "Store credentials for plugins to use; values are never returned")
@SecurityRequirement(name = "adminApiKey")
public class SecretController {

    private final SecretService secretService;

    public SecretController(SecretService secretService) {
        this.secretService = secretService;
    }

    @GetMapping
    @Operation(summary = "List secret names and metadata",
            description = "Never returns values. Includes read counts so an operator can see whether a credential is "
                    + "actually being used.")
    public List<SecretService.SecretSummary> list() {
        return secretService.list();
    }

    @PutMapping("/{name}")
    @Operation(summary = "Create or replace a secret",
            description = "The value is encrypted with AES-GCM before it is stored. Use allowedPlugins to restrict "
                    + "which plugins may read it, in addition to the secret scopes granted at plugin upload time.")
    public ResponseEntity<Void> put(
            @PathVariable String name,
            @Valid @RequestBody SecretRequest request,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        secretService.write(name, request.value(), request.description(), request.allowedPlugins(),
                ActorResolver.resolve(actorHeader));
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @DeleteMapping("/{name}")
    @Operation(summary = "Delete a secret")
    public ResponseEntity<Void> delete(
            @PathVariable String name,
            @RequestHeader(value = ActorResolver.ACTOR_HEADER, required = false) String actorHeader) {
        boolean deleted = secretService.delete(name, ActorResolver.resolve(actorHeader));
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/status")
    @Operation(summary = "Report whether secret storage is usable",
            description = "False when no master key is configured, in which case writes are rejected and plugins "
                    + "needing credentials will fail.")
    public SecretStatus status() {
        return new SecretStatus(secretService.isConfigured(), secretService.list().size());
    }

    /**
     * Whether secret storage is available.
     *
     * @param configured whether a master key is present
     * @param count      how many secrets are stored
     */
    public record SecretStatus(boolean configured, int count) {
    }
}
