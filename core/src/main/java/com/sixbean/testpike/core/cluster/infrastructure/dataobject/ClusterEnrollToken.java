package com.sixbean.testpike.core.cluster.infrastructure.dataobject;

import java.time.LocalDateTime;

/**
 * Cluster enrollment one-time token data object (tp_cluster_enroll_token, migration artifact) follows the frozen-document security red line.
 * Stores only a SHA-256 digest, one token per machine, and binds TTL to the machine fingerprint.
 * <p>The DO must not leave the API layer (service layering rule), and the status value domain is 0 pending, 1 redeemed, 2 expired, or 3 revoked.
 */
public class ClusterEnrollToken {

    private Long id;
    private String tokenDigest;
    private String role;
    private String projectScope;
    private int maxUses;
    private int usedCount;
    private int status;
    private String boundFingerprint;
    private LocalDateTime expiresAt;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTokenDigest() {
        return tokenDigest;
    }

    public void setTokenDigest(String tokenDigest) {
        this.tokenDigest = tokenDigest;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getProjectScope() {
        return projectScope;
    }

    public void setProjectScope(String projectScope) {
        this.projectScope = projectScope;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public void setMaxUses(int maxUses) {
        this.maxUses = maxUses;
    }

    public int getUsedCount() {
        return usedCount;
    }

    public void setUsedCount(int usedCount) {
        this.usedCount = usedCount;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getBoundFingerprint() {
        return boundFingerprint;
    }

    public void setBoundFingerprint(String boundFingerprint) {
        this.boundFingerprint = boundFingerprint;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
