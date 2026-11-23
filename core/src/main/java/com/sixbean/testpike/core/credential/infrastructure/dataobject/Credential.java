package com.sixbean.testpike.core.credential.infrastructure.dataobject;

import java.time.LocalDateTime;


public class Credential {

    private Long id;
    private Long projectId;
    private String name;
    private String credKey;
    private String secretCipher;
    private Integer status;
    private String remark;
    /** Last "test now" evidence grade (column test_evidence; null = never tested). */
    private String testEvidence;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCredKey() {
        return credKey;
    }

    public void setCredKey(String credKey) {
        this.credKey = credKey;
    }

    public String getSecretCipher() {
        return secretCipher;
    }

    public void setSecretCipher(String secretCipher) {
        this.secretCipher = secretCipher;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getTestEvidence() {
        return testEvidence;
    }

    public void setTestEvidence(String testEvidence) {
        this.testEvidence = testEvidence;
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
