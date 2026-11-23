package com.sixbean.testpike.core.mock.infrastructure.dataobject;

import java.time.LocalDateTime;

/**
 * Data object for tp_mock_recording (cassette boxes of the recording control
 * plane). summary_json is read as {@code summary_json::text AS summaryJson}
 * so BeanPropertyRowMapper maps jsonb without a type handler; the DO never
 * leaves the api layer.
 */
public class MockRecordingDO {

    private Long id;
    private Long spaceId;
    private String targetBaseUrl;
    private String status;
    private String summaryJson;
    private String cassetteRef;
    private Integer promotedCount;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
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

    public String getTargetBaseUrl() {
        return targetBaseUrl;
    }

    public void setTargetBaseUrl(String targetBaseUrl) {
        this.targetBaseUrl = targetBaseUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSummaryJson() {
        return summaryJson;
    }

    public void setSummaryJson(String summaryJson) {
        this.summaryJson = summaryJson;
    }

    public String getCassetteRef() {
        return cassetteRef;
    }

    public void setCassetteRef(String cassetteRef) {
        this.cassetteRef = cassetteRef;
    }

    public Integer getPromotedCount() {
        return promotedCount;
    }

    public void setPromotedCount(Integer promotedCount) {
        this.promotedCount = promotedCount;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
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
