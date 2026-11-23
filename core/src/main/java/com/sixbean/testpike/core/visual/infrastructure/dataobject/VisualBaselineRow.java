package com.sixbean.testpike.core.visual.infrastructure.dataobject;

import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.visual.api.dto.VisualBaselineInfo;
import com.sixbean.testpike.core.visual.domain.component.VisualBaselineStatus;

import java.sql.Timestamp;


public class VisualBaselineRow {

    private Long id;
    private Long projectId;
    private String stepKey;
    private Long sourceTaskId;
    private String sourceArtifactKey;
    private String baselineKey;
    private Integer status;
    private Double diffTolerance;
    private Long confirmedBy;
    private Timestamp createdAt;
    private Timestamp confirmedAt;

    
    public VisualBaselineInfo toInfo() {
        return new VisualBaselineInfo(id, projectId, stepKey, sourceTaskId, sourceArtifactKey,
                baselineKey, status == null ? 0 : status, VisualBaselineStatus.label(status),
                diffTolerance, confirmedBy,
                ViewFormats.formatTs(createdAt), ViewFormats.formatTs(confirmedAt));
    }

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

    public String getStepKey() {
        return stepKey;
    }

    public void setStepKey(String stepKey) {
        this.stepKey = stepKey;
    }

    public Long getSourceTaskId() {
        return sourceTaskId;
    }

    public void setSourceTaskId(Long sourceTaskId) {
        this.sourceTaskId = sourceTaskId;
    }

    public String getSourceArtifactKey() {
        return sourceArtifactKey;
    }

    public void setSourceArtifactKey(String sourceArtifactKey) {
        this.sourceArtifactKey = sourceArtifactKey;
    }

    public String getBaselineKey() {
        return baselineKey;
    }

    public void setBaselineKey(String baselineKey) {
        this.baselineKey = baselineKey;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Double getDiffTolerance() {
        return diffTolerance;
    }

    public void setDiffTolerance(Double diffTolerance) {
        this.diffTolerance = diffTolerance;
    }

    public Long getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(Long confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Timestamp confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
