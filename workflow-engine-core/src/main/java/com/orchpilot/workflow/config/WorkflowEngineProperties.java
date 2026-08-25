package com.orchpilot.workflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Every tunable of the engine, bound from {@code workflow.engine.*}.
 *
 * <p>No credential is ever defaulted here. The Mongo URI, the admin API key and the secret master
 * key all come from the environment; the engine refuses to start with a placeholder in place of the
 * master key.
 */
@ConfigurationProperties(prefix = "workflow.engine")
public class WorkflowEngineProperties {

    /** Logical name of this instance, used to attribute execution ownership for crash recovery. */
    private String instanceId = "";

    private final Execution execution = new Execution();
    private final Plugins plugins = new Plugins();
    private final Scheduler scheduler = new Scheduler();
    private final Security security = new Security();
    private final Secrets secrets = new Secrets();
    private final ImportExport importExport = new ImportExport();
    private final ExternalForm externalForm = new ExternalForm();

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public Execution getExecution() {
        return execution;
    }

    public Plugins getPlugins() {
        return plugins;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public Security getSecurity() {
        return security;
    }

    public Secrets getSecrets() {
        return secrets;
    }

    public ImportExport getImportExport() {
        return importExport;
    }

    public ExternalForm getExternalForm() {
        return externalForm;
    }

    /** Execution engine and thread pool tuning. */
    public static class Execution {

        /** How long a synchronous execute request waits before it is turned into an async one. */
        private long syncTimeoutMillis = 60_000;

        /** Hard ceiling on nodes executed per run; guards against runaway loops in cyclic graphs. */
        private int maxSteps = 1_000;

        /** Default per-node execution budget when the node does not declare one. */
        private long defaultNodeTimeoutMillis = 60_000;

        private int corePoolSize = 8;
        private int maxPoolSize = 32;
        private int queueCapacity = 500;

        /** How often a running execution refreshes its heartbeat. */
        private long heartbeatIntervalMillis = 15_000;

        /** Age of heartbeat after which another instance may reclaim a RUNNING execution. */
        private long staleAfterMillis = 120_000;

        /** Whether to reclaim executions abandoned by a crashed instance at startup. */
        private boolean recoveryEnabled = true;

        /** Delay before the first recovery sweep, giving the cluster time to settle. */
        private long recoveryInitialDelayMillis = 30_000;

        public long getSyncTimeoutMillis() {
            return syncTimeoutMillis;
        }

        public void setSyncTimeoutMillis(long syncTimeoutMillis) {
            this.syncTimeoutMillis = syncTimeoutMillis;
        }

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public long getDefaultNodeTimeoutMillis() {
            return defaultNodeTimeoutMillis;
        }

        public void setDefaultNodeTimeoutMillis(long defaultNodeTimeoutMillis) {
            this.defaultNodeTimeoutMillis = defaultNodeTimeoutMillis;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public long getHeartbeatIntervalMillis() {
            return heartbeatIntervalMillis;
        }

        public void setHeartbeatIntervalMillis(long heartbeatIntervalMillis) {
            this.heartbeatIntervalMillis = heartbeatIntervalMillis;
        }

        public long getStaleAfterMillis() {
            return staleAfterMillis;
        }

        public void setStaleAfterMillis(long staleAfterMillis) {
            this.staleAfterMillis = staleAfterMillis;
        }

        public boolean isRecoveryEnabled() {
            return recoveryEnabled;
        }

        public void setRecoveryEnabled(boolean recoveryEnabled) {
            this.recoveryEnabled = recoveryEnabled;
        }

        public long getRecoveryInitialDelayMillis() {
            return recoveryInitialDelayMillis;
        }

        public void setRecoveryInitialDelayMillis(long recoveryInitialDelayMillis) {
            this.recoveryInitialDelayMillis = recoveryInitialDelayMillis;
        }
    }

    /** Plugin platform configuration. */
    public static class Plugins {

        /** Root directory for per-version plugin workspaces; JARs are staged here from GridFS. */
        private String workspaceDirectory = "";

        /** Largest plugin JAR the engine accepts, in bytes. */
        private long maxJarBytes = 64L * 1024 * 1024;

        /** Largest number of entries a plugin JAR may contain; guards against zip bombs. */
        private int maxJarEntries = 20_000;

        /** Largest total uncompressed size of a plugin JAR; guards against zip bombs. */
        private long maxUncompressedBytes = 512L * 1024 * 1024;

        /** How long deactivation waits for in-flight executions before applying the drain policy. */
        private long unloadGraceMillis = 30_000;

        /** Whether ACTIVE plugin versions are reloaded from GridFS at startup. */
        private boolean autoLoadOnStartup = true;

        /** Require the uploader to supply a SHA-256 that matches the received bytes. */
        private boolean requireChecksum = false;

        /** Require the JAR to be signed and every class entry to carry a verified signature. */
        private boolean requireSignature = false;

        /** Hosts every plugin may call when it declares no allowlist of its own. Empty denies all. */
        private List<String> defaultAllowedHosts = new ArrayList<>();

        /** Ceiling applied to any per-request timeout a plugin asks for. */
        private long httpMaxTimeoutMillis = 60_000;

        /** Response bodies larger than this are rejected rather than buffered. */
        private long httpMaxResponseBytes = 8L * 1024 * 1024;

        /** Ceiling on rows returned by a plugin data-store query. */
        private int dataStoreMaxResults = 500;

        /** Whether plugin request/response payloads are persisted to plugin_executions. */
        private boolean recordPayloads = true;

        /** Characters of a plugin payload retained in plugin_executions. */
        private int payloadMaxChars = 4_000;

        public String getWorkspaceDirectory() {
            return workspaceDirectory;
        }

        public void setWorkspaceDirectory(String workspaceDirectory) {
            this.workspaceDirectory = workspaceDirectory;
        }

        public long getMaxJarBytes() {
            return maxJarBytes;
        }

        public void setMaxJarBytes(long maxJarBytes) {
            this.maxJarBytes = maxJarBytes;
        }

        public int getMaxJarEntries() {
            return maxJarEntries;
        }

        public void setMaxJarEntries(int maxJarEntries) {
            this.maxJarEntries = maxJarEntries;
        }

        public long getMaxUncompressedBytes() {
            return maxUncompressedBytes;
        }

        public void setMaxUncompressedBytes(long maxUncompressedBytes) {
            this.maxUncompressedBytes = maxUncompressedBytes;
        }

        public long getUnloadGraceMillis() {
            return unloadGraceMillis;
        }

        public void setUnloadGraceMillis(long unloadGraceMillis) {
            this.unloadGraceMillis = unloadGraceMillis;
        }

        public boolean isAutoLoadOnStartup() {
            return autoLoadOnStartup;
        }

        public void setAutoLoadOnStartup(boolean autoLoadOnStartup) {
            this.autoLoadOnStartup = autoLoadOnStartup;
        }

        public boolean isRequireChecksum() {
            return requireChecksum;
        }

        public void setRequireChecksum(boolean requireChecksum) {
            this.requireChecksum = requireChecksum;
        }

        public boolean isRequireSignature() {
            return requireSignature;
        }

        public void setRequireSignature(boolean requireSignature) {
            this.requireSignature = requireSignature;
        }

        public List<String> getDefaultAllowedHosts() {
            return defaultAllowedHosts;
        }

        public void setDefaultAllowedHosts(List<String> defaultAllowedHosts) {
            this.defaultAllowedHosts = defaultAllowedHosts == null ? new ArrayList<>() : defaultAllowedHosts;
        }

        public long getHttpMaxTimeoutMillis() {
            return httpMaxTimeoutMillis;
        }

        public void setHttpMaxTimeoutMillis(long httpMaxTimeoutMillis) {
            this.httpMaxTimeoutMillis = httpMaxTimeoutMillis;
        }

        public long getHttpMaxResponseBytes() {
            return httpMaxResponseBytes;
        }

        public void setHttpMaxResponseBytes(long httpMaxResponseBytes) {
            this.httpMaxResponseBytes = httpMaxResponseBytes;
        }

        public int getDataStoreMaxResults() {
            return dataStoreMaxResults;
        }

        public void setDataStoreMaxResults(int dataStoreMaxResults) {
            this.dataStoreMaxResults = dataStoreMaxResults;
        }

        public boolean isRecordPayloads() {
            return recordPayloads;
        }

        public void setRecordPayloads(boolean recordPayloads) {
            this.recordPayloads = recordPayloads;
        }

        public int getPayloadMaxChars() {
            return payloadMaxChars;
        }

        public void setPayloadMaxChars(int payloadMaxChars) {
            this.payloadMaxChars = payloadMaxChars;
        }
    }

    /** Cron scheduler configuration. */
    public static class Scheduler {

        private boolean enabled = true;

        /** How often the cluster-safe poller looks for due schedules. */
        private long pollIntervalMillis = 10_000;

        /** Maximum schedules claimed per poll. */
        private int batchSize = 25;

        /** A schedule whose fire time is older than this is skipped rather than fired late. */
        private long misfireThresholdMillis = 300_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getPollIntervalMillis() {
            return pollIntervalMillis;
        }

        public void setPollIntervalMillis(long pollIntervalMillis) {
            this.pollIntervalMillis = pollIntervalMillis;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getMisfireThresholdMillis() {
            return misfireThresholdMillis;
        }

        public void setMisfireThresholdMillis(long misfireThresholdMillis) {
            this.misfireThresholdMillis = misfireThresholdMillis;
        }
    }

    /** Coarse protection for administrative endpoints. */
    public static class Security {

        /**
         * Whether browsers may send credentials on cross-origin API calls.
         *
         * <p>Only needed when the console is served from a different origin than the API and the refresh
         * token travels as a cookie, since a cross-origin cookie requires credentials mode. Leave it off
         * for the same-origin deployments, where it buys nothing and widens what a hostile page can do.
         */
        private boolean allowCredentials = false;

        /**
         * Browser origins allowed to call the API directly.
         *
         * Needed when the console is served from a different origin than the engine, which is the normal
         * case during development: the Angular dev server on 4200 calling the engine on 8080 is
         * cross-origin, and without this the browser blocks every request before the engine sees it.
         *
         * Defaults to the local dev-server origins only. In production, either serve the console from the
         * same origin through a reverse proxy, which needs no entry here, or list the exact origins.
         * Never use a wildcard: these endpoints install code and read credentials.
         */
        private List<String> allowedOrigins = new ArrayList<>(List.of(
                "http://localhost:4200", "http://127.0.0.1:4200",
                "http://localhost:4300", "http://127.0.0.1:4300"));

        public boolean isAllowCredentials() {
            return allowCredentials;
        }

        public void setAllowCredentials(boolean allowCredentials) {
            this.allowCredentials = allowCredentials;
        }

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? new ArrayList<>() : allowedOrigins;
        }
    }

    /** Secret storage configuration. */
    public static class Secrets {

        /**
         * Base64-encoded 256-bit AES key used to encrypt secret values at rest. Supply it from the
         * environment. When empty, secret write operations are rejected.
         */
        private String masterKey = "";

        /** Identifier recorded on each encrypted value so keys can be rotated. */
        private String keyId = "default";

        public String getMasterKey() {
            return masterKey;
        }

        public void setMasterKey(String masterKey) {
            this.masterKey = masterKey;
        }

        public String getKeyId() {
            return keyId;
        }

        public void setKeyId(String keyId) {
            this.keyId = keyId;
        }
    }

    /** External / public form links. */
    public static class ExternalForm {

        /**
         * Base URL a generated link is built on. The raw token is appended, so a link is
         * {@code {baseUrl}{token}}. Defaults to a relative path, which resolves against whatever origin serves
         * the console; set an absolute {@code https://app.example.com/public/form/} to email links that work
         * from anywhere.
         */
        private String baseUrl = "/public/form/";

        /** Default link lifetime in hours when the node or the request does not specify one. */
        private long defaultExpirationHours = 24;

        /** Requests per minute per client IP allowed on the public form endpoints. */
        private int rateLimitPerMinute = 60;

        /** The stricter per-minute-per-IP ceiling on the submit endpoint specifically. */
        private int submitRateLimitPerMinute = 10;

        /**
         * File extensions never accepted on an external upload, whatever a field allows. The blocklist is
         * enforced even though full upload handling (storage, MIME sniffing, AV scan) is a separate seam.
         */
        private java.util.List<String> blockedFileExtensions = new java.util.ArrayList<>(java.util.List.of(
                "exe", "bat", "cmd", "ps1", "sh", "com", "scr", "msi", "jar", "dll"));

        /** Whether external forms require a CAPTCHA. Off by default; the verifier is a seam with no provider. */
        private boolean captchaRequired = false;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public long getDefaultExpirationHours() {
            return defaultExpirationHours;
        }

        public void setDefaultExpirationHours(long defaultExpirationHours) {
            this.defaultExpirationHours = defaultExpirationHours;
        }

        public int getRateLimitPerMinute() {
            return rateLimitPerMinute;
        }

        public void setRateLimitPerMinute(int rateLimitPerMinute) {
            this.rateLimitPerMinute = rateLimitPerMinute;
        }

        public int getSubmitRateLimitPerMinute() {
            return submitRateLimitPerMinute;
        }

        public void setSubmitRateLimitPerMinute(int submitRateLimitPerMinute) {
            this.submitRateLimitPerMinute = submitRateLimitPerMinute;
        }

        public java.util.List<String> getBlockedFileExtensions() {
            return blockedFileExtensions;
        }

        public void setBlockedFileExtensions(java.util.List<String> blockedFileExtensions) {
            this.blockedFileExtensions = blockedFileExtensions;
        }

        public boolean isCaptchaRequired() {
            return captchaRequired;
        }

        public void setCaptchaRequired(boolean captchaRequired) {
            this.captchaRequired = captchaRequired;
        }
    }

    /** Workflow import/export (the {@code .orchpilot} portability format). */
    public static class ImportExport {

        /**
         * Largest {@code .orchpilot} file accepted for import, in bytes. Enforced before decryption so a
         * hostile or corrupt file cannot exhaust memory; defaults to 50&nbsp;MB.
         */
        private long maxFileBytes = 50L * 1024 * 1024;

        public long getMaxFileBytes() {
            return maxFileBytes;
        }

        public void setMaxFileBytes(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }
    }
}
