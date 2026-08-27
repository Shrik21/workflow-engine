package com.orchpilot.pluginserver.security;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A machine that may call the registry.
 *
 * <h2>The secret is stored hashed</h2>
 *
 * <p>The field is {@code secretHash} and there is no accessor that returns a usable secret, because this service
 * must not be able to produce one. A registry that could print its clients' credentials is a registry whose
 * database dump is a set of working credentials for every workflow service in the estate. The plaintext exists
 * once, at registration, in the response to whoever registered it, and never again.
 *
 * <h2>Why BCrypt rather than Argon2</h2>
 *
 * <p>The platform hashes user passwords with Argon2id, and this deliberately does not. Argon2's memory hardness
 * exists to make brute-forcing human-chosen passwords expensive; a client secret here is 256 bits of output from a
 * cryptographic RNG, and no amount of memory hardness matters against a search space that size. What does matter
 * is that verification happens on every token request from every service, and BCrypt at a sane cost is fast enough
 * to not become the thing that limits sync throughput.
 */
@Document(collection = "service_clients")
public class ServiceClient {

    /** The client id, which is also the token's subject. */
    @Id
    private String clientId;

    private String description;

    /** BCrypt digest of the secret. Never the secret. */
    private String secretHash;

    /**
     * Authorities this client's tokens carry.
     *
     * <p>Stored per client rather than derived from a role, so a client can be given exactly what it needs. The
     * default is read and download; nothing here should be able to upload.
     */
    private Set<PluginAuthority> authorities = new LinkedHashSet<>();

    private boolean enabled = true;

    private Instant createdAt;
    private String createdBy;

    /** Updated on each successful token issue, so an unused client is identifiable. */
    private Instant lastUsedAt;

    private long tokensIssued;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSecretHash() {
        return secretHash;
    }

    public void setSecretHash(String secretHash) {
        this.secretHash = secretHash;
    }

    public Set<PluginAuthority> getAuthorities() {
        return authorities;
    }

    public void setAuthorities(Set<PluginAuthority> authorities) {
        this.authorities = authorities == null ? new LinkedHashSet<>() : new LinkedHashSet<>(authorities);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public long getTokensIssued() {
        return tokensIssued;
    }

    public void setTokensIssued(long tokensIssued) {
        this.tokensIssued = tokensIssued;
    }

    /** @return the authority names to put in the token */
    public List<String> authorityNames() {
        List<String> names = new ArrayList<>(authorities.size());
        authorities.forEach(authority -> names.add(authority.authority()));
        return names;
    }

    @Override
    public String toString() {
        // Deliberately without the hash. This ends up in log lines.
        return "ServiceClient{" + clientId + " enabled=" + enabled + " authorities=" + authorities + "}";
    }
}
