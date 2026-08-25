package com.orchpilot.workflow.storage.provider;

import com.orchpilot.workflow.storage.exception.FileStorageException;
import com.orchpilot.workflow.storage.model.StorageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the provider that can resolve a given {@link StorageType}.
 *
 * <h2>Why dispatch per reference rather than per deployment</h2>
 *
 * The obvious design injects one provider chosen by configuration. It breaks the first time a deployment
 * migrates: files written to local disk before the switch still have to be readable after it. Because every
 * {@link com.orchpilot.workflow.storage.model.WorkflowFileReference} records the type that wrote it, dispatching
 * per reference means a migration is additive — new files go to the new provider, old ones keep resolving — and
 * needs no backfill.
 *
 * <p>Providers are discovered from the Spring context, so adding {@code S3FileStorageProvider} is a new
 * {@code @Component} and nothing else. Nothing here enumerates them.
 */
@Component
public class FileStorageProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(FileStorageProviderRegistry.class);

    private final Map<StorageType, FileStorageProvider> providers = new EnumMap<>(StorageType.class);

    public FileStorageProviderRegistry(List<FileStorageProvider> discovered) {
        for (FileStorageProvider provider : discovered) {
            FileStorageProvider previous = providers.put(provider.storageType(), provider);
            if (previous != null) {
                // Two providers for one type means one silently wins, and which one would depend on bean
                // ordering. Better to say so at start-up than to debug it from inconsistent file locations.
                log.warn("Two providers claim storage type {}: {} replaced {}", provider.storageType(),
                        provider.getClass().getSimpleName(), previous.getClass().getSimpleName());
            }
        }
        log.info("File storage providers available: {}", providers.keySet());
    }

    /**
     * @param storageType the type to resolve
     * @return the provider for it
     * @throws FileStorageException when no provider ships for that type
     */
    public FileStorageProvider require(StorageType storageType) {
        FileStorageProvider provider = providers.get(storageType);
        if (provider == null) {
            throw FileStorageException.unsupportedProvider(String.valueOf(storageType));
        }
        return provider;
    }

    /** @return the types that can actually be configured today, for the settings screen's dropdown */
    public Set<StorageType> available() {
        return Set.copyOf(providers.keySet());
    }
}
