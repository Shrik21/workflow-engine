package com.orchpilot.workflow.externalform;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A single-purpose credential that lets one external customer open, draft and submit one form task — without
 * an OrchPilot account.
 *
 * <h2>The raw token is never here</h2>
 *
 * The random token handed to the customer lives only in their URL. What is stored is {@link #tokenHash}, the
 * SHA-256 of that token, so a database compromise yields no working links: an attacker would have to reverse a
 * SHA-256 of 256 bits of entropy. Lookup hashes the incoming token and matches this column, which is uniquely
 * indexed.
 *
 * <h2>What it binds, and what the customer can never choose</h2>
 *
 * The token binds to exactly one task, one instance and one tenant. The customer supplies only the token; the
 * task, instance and tenant are read from here, never from the request, which is what makes cross-tenant and
 * cross-task access impossible rather than merely guarded — there is nothing in the request to tamper with.
 */
@Document("externalFormAccessTokens")
public class ExternalFormAccessToken {

    @Id
    private String id;

    /** SHA-256 of the raw token, hex-encoded. Unique: one live link per hash. */
    @Indexed(unique = true)
    private String tokenHash;

    /** The human task this link completes. */
    @Indexed
    private String taskId;

    /** The workflow instance (execution) the task belongs to. */
    @Indexed
    private String workflowInstanceId;

    /** The tenant, resolved from the workflow at creation. Provenance and defence-in-depth, never trusted from a request. */
    @Indexed
    private String tenantId;

    private ExternalFormTokenStatus status = ExternalFormTokenStatus.ACTIVE;

    @Indexed
    private Instant expiresAt;

    /** How many successful submissions the token allows. One, unless an administrator configured more. */
    private int maxSubmissions = 1;

    /** How many successful submissions have happened. */
    private int submissionCount;

    /** Whether the form may be submitted / drafted through this link (mirrors the node configuration). */
    private boolean allowSubmit = true;
    private boolean allowDraft = true;

    private Instant createdAt;
    private String createdBy;
    private Instant usedAt;
    private Instant revokedAt;
    private String revokedBy;

    // Optional, non-sensitive customer identification captured at generation. Never a secret.
    private String customerName;
    private String customerEmail;
    private String customerReference;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getWorkflowInstanceId() {
        return workflowInstanceId;
    }

    public void setWorkflowInstanceId(String workflowInstanceId) {
        this.workflowInstanceId = workflowInstanceId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public ExternalFormTokenStatus getStatus() {
        return status;
    }

    public void setStatus(ExternalFormTokenStatus status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getMaxSubmissions() {
        return maxSubmissions;
    }

    public void setMaxSubmissions(int maxSubmissions) {
        this.maxSubmissions = maxSubmissions;
    }

    public int getSubmissionCount() {
        return submissionCount;
    }

    public void setSubmissionCount(int submissionCount) {
        this.submissionCount = submissionCount;
    }

    public boolean isAllowSubmit() {
        return allowSubmit;
    }

    public void setAllowSubmit(boolean allowSubmit) {
        this.allowSubmit = allowSubmit;
    }

    public boolean isAllowDraft() {
        return allowDraft;
    }

    public void setAllowDraft(boolean allowDraft) {
        this.allowDraft = allowDraft;
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

    public Instant getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Instant usedAt) {
        this.usedAt = usedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public void setRevokedBy(String revokedBy) {
        this.revokedBy = revokedBy;
    }

    public String getCustomerName() {
        return customerName;
    }

    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public String getCustomerReference() {
        return customerReference;
    }

    public void setCustomerReference(String customerReference) {
        this.customerReference = customerReference;
    }
}
