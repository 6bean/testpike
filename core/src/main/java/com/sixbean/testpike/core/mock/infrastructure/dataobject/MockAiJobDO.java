package com.sixbean.testpike.core.mock.infrastructure.dataobject;

import java.time.LocalDateTime;

/**
 * Data object for tp_mock_ai_job (AI drafting job ledger rows). JSONB columns
 * are read as {@code col::text AS camelName} so BeanPropertyRowMapper maps
 * them without a type handler; the DO never leaves the api layer.
 */
public class MockAiJobDO {

    private Long id;
    private Long spaceId;
    private String jobType;
    private String status;
    private String inputJson;
    private String budgetSnapshotJson;
    private String resultPreviewRef;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInputJson() {
        return inputJson;
    }

    public void setInputJson(String inputJson) {
        this.inputJson = inputJson;
    }

    public String getBudgetSnapshotJson() {
        return budgetSnapshotJson;
    }

    public void setBudgetSnapshotJson(String budgetSnapshotJson) {
        this.budgetSnapshotJson = budgetSnapshotJson;
    }

    public String getResultPreviewRef() {
        return resultPreviewRef;
    }

    public void setResultPreviewRef(String resultPreviewRef) {
        this.resultPreviewRef = resultPreviewRef;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
