package com.orchpilot.workflow.portability;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;

/**
 * Serialises a {@link WorkflowPackage} to bytes and back, with a mapper hardened against the
 * deserialization-gadget attack the specification names.
 *
 * <h2>Why this mapper, specifically</h2>
 *
 * The danger in deserializing an untrusted document is not the data but the <em>types</em>: a mapper with
 * polymorphic default typing enabled reads a class name out of the document and instantiates it, which is the
 * whole mechanism behind Java deserialization exploits. This mapper never does that — default typing is off
 * (Jackson's default, asserted here by never enabling it) — so every field lands in the fixed types
 * {@link WorkflowPackage} declares, and the free-form {@code Map<String,Object>} fields become plain maps,
 * lists, strings, numbers and booleans. No class named in the payload is ever loaded. Unknown properties are
 * ignored rather than rejected, so a package written by a newer version still imports.
 */
final class PackageCodec {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            // Default typing is never enabled: no type information is read from the document, so no gadget
            // class can be instantiated. This line documents the guarantee; there is no activateDefaultTyping.
            .build();

    byte[] serialize(WorkflowPackage workflowPackage) {
        try {
            return mapper.writeValueAsBytes(workflowPackage);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize the workflow package", ex);
        }
    }

    /**
     * Reads a package from decrypted bytes.
     *
     * @param bytes the decrypted payload
     * @return the package
     * @throws PackageIntegrityException when the bytes are not a valid package document
     */
    WorkflowPackage deserialize(byte[] bytes) {
        try {
            WorkflowPackage parsed = mapper.readValue(bytes, WorkflowPackage.class);
            if (parsed == null) {
                throw new PackageIntegrityException("The package payload is empty.");
            }
            return parsed;
        } catch (PackageIntegrityException ex) {
            throw ex;
        } catch (Exception ex) {
            // The payload decrypted (the tag verified) but is not a package we understand: a version mismatch
            // or a deliberately malformed body. Not a crypto failure, a schema one.
            throw new PackageIntegrityException(
                    "The package decrypted but its contents are not a valid workflow package.", ex);
        }
    }

    /** @return the raw JSON, for logging the shape in tests only — never the encrypted-file path */
    String toJson(WorkflowPackage workflowPackage) {
        return new String(serialize(workflowPackage), StandardCharsets.UTF_8);
    }
}
