package com.orchpilot.workflow.service;

import com.orchpilot.workflow.config.WorkflowEngineProperties;
import com.orchpilot.workflow.exception.SecretAccessException;
import com.orchpilot.workflow.model.SecretRecord;
import com.orchpilot.workflow.repository.SecretRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * AES-GCM secret storage.
 *
 * <p>AES-GCM rather than AES-CBC because it is authenticated: an attacker with write access to MongoDB
 * cannot flip bits in a ciphertext to steer a plugin at a different endpoint without the tag check
 * failing. A fresh 96-bit nonce is generated per write, which is mandatory for GCM: reusing a nonce with
 * the same key is a catastrophic failure, not a small one.
 *
 * <p>The master key comes from {@code workflow.engine.secrets.master-key}, which must be supplied from
 * the environment. When it is absent the service starts, refuses writes, and logs a warning, so a
 * developer can run the engine without secrets configured but cannot accidentally store credentials in
 * plaintext.
 *
 * <p>{@code keyId} is recorded on every value so that rotating the master key can be done by
 * re-encrypting values one at a time rather than in a single flag-day migration.
 */
@Service
public class AesGcmSecretService implements SecretService {

    private static final Logger log = LoggerFactory.getLogger(AesGcmSecretService.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretRepository repository;
    private final MongoTemplate mongoTemplate;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();
    private final SecretKey masterKey;
    private final String keyId;

    public AesGcmSecretService(SecretRepository repository, MongoTemplate mongoTemplate,
                              AuditService auditService, WorkflowEngineProperties properties) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.auditService = auditService;
        this.keyId = properties.getSecrets().getKeyId();
        this.masterKey = loadKey(properties.getSecrets().getMasterKey());
        if (this.masterKey == null) {
            log.warn("workflow.engine.secrets.master-key is not set. Secret storage is disabled: plugins "
                    + "requiring credentials will fail. Set a base64-encoded 128, 192 or 256 bit key.");
        }
    }

    @Override
    public boolean isConfigured() {
        return masterKey != null;
    }

    @Override
    public Optional<String> read(String name, String pluginId) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Optional<SecretRecord> found = repository.findById(name);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SecretRecord record = found.get();
        if (pluginId != null && !record.getAllowedPlugins().isEmpty()
                && !record.getAllowedPlugins().contains(pluginId)) {
            auditService.record(pluginId, "SECRET_READ_DENIED", "SECRET", name, "DENIED", null);
            throw new SecretAccessException("Secret '" + name + "' is not readable by plugin '"
                    + pluginId + "'");
        }
        if (masterKey == null) {
            throw new SecretAccessException("Cannot decrypt secret '" + name
                    + "': no master key is configured");
        }
        String value = decrypt(record);
        recordRead(name);
        if (pluginId != null) {
            auditService.record(pluginId, "SECRET_READ", "SECRET", name, "OK", null);
        }
        return Optional.of(value);
    }

    @Override
    public void write(String name, String value, String description, List<String> allowedPlugins,
                      String actor) {
        if (name == null || name.isBlank()) {
            throw new SecretAccessException("A secret name is required");
        }
        if (value == null) {
            throw new SecretAccessException("A secret value is required");
        }
        if (masterKey == null) {
            throw new SecretAccessException("Cannot store secret '" + name
                    + "': set workflow.engine.secrets.master-key first");
        }
        SecretRecord record = repository.findById(name).orElseGet(SecretRecord::new);
        boolean isNew = record.getName() == null;
        record.setName(name);
        if (description != null) {
            record.setDescription(description);
        }
        record.setAllowedPlugins(allowedPlugins == null ? new ArrayList<>() : new ArrayList<>(allowedPlugins));
        record.setAlgorithm(TRANSFORMATION);
        record.setKeyId(keyId);
        encryptInto(record, value);
        Instant now = Instant.now();
        if (isNew) {
            record.setCreatedAt(now);
        }
        record.setUpdatedAt(now);
        record.setUpdatedBy(actor);
        repository.save(record);
        auditService.record(actor, isNew ? "SECRET_CREATED" : "SECRET_UPDATED", "SECRET", name, "OK",
                java.util.Map.of("allowedPlugins", record.getAllowedPlugins()));
        log.info("Secret '{}' {} by {}", name, isNew ? "created" : "updated", actor);
    }

    @Override
    public boolean delete(String name, String actor) {
        if (name == null || !repository.existsById(name)) {
            return false;
        }
        repository.deleteById(name);
        auditService.record(actor, "SECRET_DELETED", "SECRET", name, "OK", null);
        log.info("Secret '{}' deleted by {}", name, actor);
        return true;
    }

    @Override
    public List<SecretSummary> list() {
        List<SecretSummary> summaries = new ArrayList<>();
        for (SecretRecord record : repository.findAll()) {
            summaries.add(new SecretSummary(record.getName(), record.getDescription(),
                    List.copyOf(record.getAllowedPlugins()), record.getKeyId(),
                    record.getUpdatedAt() == null ? null : record.getUpdatedAt().toString(),
                    record.getReadCount()));
        }
        return summaries;
    }

    // --------------------------------------------------------------- crypto

    private void encryptInto(SecretRecord record, String plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            // The secret name is authenticated but not encrypted, so a ciphertext cannot be moved from
            // one secret to another without the tag check failing.
            cipher.updateAAD(record.getName().getBytes(StandardCharsets.UTF_8));
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            record.setNonce(Base64.getEncoder().encodeToString(nonce));
            record.setCipherText(Base64.getEncoder().encodeToString(cipherText));
        } catch (GeneralSecurityException ex) {
            throw new SecretAccessException("Could not encrypt secret '" + record.getName() + "'", ex);
        }
    }

    private String decrypt(SecretRecord record) {
        try {
            byte[] nonce = Base64.getDecoder().decode(record.getNonce());
            byte[] cipherText = Base64.getDecoder().decode(record.getCipherText());
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(record.getName().getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new SecretAccessException("Could not decrypt secret '" + record.getName()
                    + "'. The master key may have changed or the value may have been tampered with.", ex);
        }
    }

    private static SecretKey loadKey(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "workflow.engine.secrets.master-key must be base64-encoded", ex);
        }
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException("workflow.engine.secrets.master-key must decode to 16, 24 or 32 "
                    + "bytes but decoded to " + raw.length);
        }
        return new SecretKeySpec(raw, "AES");
    }

    /**
     * Increments read statistics with a targeted update, so counting a read never conflicts with an
     * operator rotating the value at the same moment.
     */
    private void recordRead(String name) {
        try {
            mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(name)),
                    new Update().inc("readCount", 1).set("lastReadAt", Instant.now()),
                    SecretRecord.class);
        } catch (RuntimeException ex) {
            log.debug("Could not update read statistics for secret '{}': {}", name, ex.getMessage());
        }
    }
}
