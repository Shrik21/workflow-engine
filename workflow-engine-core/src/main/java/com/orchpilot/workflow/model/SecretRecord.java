package com.orchpilot.workflow.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An encrypted credential.
 *
 * <p>Only the ciphertext, the random nonce and the id of the key that encrypted it are stored. The
 * master key never touches MongoDB; it comes from the environment, so a database dump alone does not
 * disclose credentials.
 *
 * <p>{@code allowedPlugins} is the second half of secret scoping. A plugin declares which secret name
 * prefixes it may read; a secret can additionally restrict which plugins may read it. Both must agree,
 * so neither an over-permissive plugin nor an over-broad secret is sufficient on its own.
 */
@Document(collection = "workflow_secrets")
public class SecretRecord {

    /** Secret name, e.g. {@code sendgrid.apiKey}. */
    @Id
    private String name;

    private String description;

    /** Base64 AES-GCM ciphertext. */
    private String cipherText;

    /** Base64 random 96-bit nonce, unique per write. */
    private String nonce;

    private String algorithm = "AES/GCM/NoPadding";

    /** Which master key encrypted this value, so keys can be rotated without a flag day. */
    private String keyId;

    /** Plugin ids permitted to read this secret. Empty means any plugin whose scope matches. */
    private List<String> allowedPlugins = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;
    private String updatedBy;
    private long readCount;
    private Instant lastReadAt;

    public SecretRecord() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCipherText() {
        return cipherText;
    }

    public void setCipherText(String cipherText) {
        this.cipherText = cipherText;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public List<String> getAllowedPlugins() {
        return allowedPlugins;
    }

    public void setAllowedPlugins(List<String> allowedPlugins) {
        this.allowedPlugins = allowedPlugins == null ? new ArrayList<>() : allowedPlugins;
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

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public long getReadCount() {
        return readCount;
    }

    public void setReadCount(long readCount) {
        this.readCount = readCount;
    }

    public Instant getLastReadAt() {
        return lastReadAt;
    }

    public void setLastReadAt(Instant lastReadAt) {
        this.lastReadAt = lastReadAt;
    }
}
