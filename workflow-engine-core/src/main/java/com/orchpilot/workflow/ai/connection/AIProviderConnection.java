package com.orchpilot.workflow.ai.connection;

import com.orchpilot.workflow.ai.AIProviderType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A named, reusable connection to an AI provider — the thing a workflow node references, so that no API key
 * ever lands in a workflow definition.
 *
 * <h2>The credential is not here</h2>
 *
 * This document stores the provider, the endpoint and non-secret settings, plus the <em>name</em> of a secret;
 * the key itself lives in the secret store, encrypted, and is never returned by any endpoint. A workflow node
 * stores only this connection's id ({@code providerConnectionId}); resolving it to a usable configuration —
 * reading the secret — happens at execution time, server-side, and the resolved key exists only for the length
 * of one request.
 */
@Document(collection = "aiProviderConnections")
public class AIProviderConnection {

    @Id
    private String id;

    /** Display name, e.g. "OpenAI Production". */
    private String name;

    private AIProviderType providerType;

    /** Base URL for self-hosted / OpenAI-compatible providers; null uses the provider's default. */
    private String endpoint;

    /** The name of the secret holding this connection's API key, or null when the provider needs none. */
    private String secretName;

    /** Provider-specific non-secret settings (region, deployment name, …). */
    private Map<String, Object> settings = new LinkedHashMap<>();

    private boolean enabled = true;

    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AIProviderType getProviderType() {
        return providerType;
    }

    public void setProviderType(AIProviderType providerType) {
        this.providerType = providerType;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getSecretName() {
        return secretName;
    }

    public void setSecretName(String secretName) {
        this.secretName = secretName;
    }

    public Map<String, Object> getSettings() {
        return settings;
    }

    public void setSettings(Map<String, Object> settings) {
        this.settings = settings == null ? new LinkedHashMap<>() : settings;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
