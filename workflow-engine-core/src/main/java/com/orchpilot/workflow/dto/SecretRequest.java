package com.orchpilot.workflow.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Payload for storing a credential.
 *
 * <p>This is the only place a plaintext credential enters the engine, and it leaves again encrypted. There is
 * no endpoint that returns a secret value: plugins receive them through the scoped provider, and nothing else
 * can read them.
 *
 * @param value          plaintext secret value
 * @param description    what it is for
 * @param allowedPlugins plugin ids permitted to read it; empty means any plugin whose declared scope matches
 */
public record SecretRequest(@NotBlank(message = "value is required") String value, String description,
                            List<String> allowedPlugins) {
}
