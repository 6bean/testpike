package com.sixbean.testpike.core.skill.infrastructure.dataobject;

import com.sixbean.testpike.core.skill.api.dto.ProjectMemoryInfo;

import java.sql.Timestamp;

/**
 * Project-memory ledger row object .
 * <p>Reflection-mapping carrier for BeanPropertyRowMapper (P7 baseline, same
 * shape as SkillRecycleRow); column aliases line up 1:1 with the properties.
 * Converted to the api-record through {@link #toInfo} — the DO never leaves
 * the infrastructure boundary.
 */
public class ProjectMemoryRow {

    private Long id;
    private Long projectId;
    private String content;
    private String sourceRef;
    private Integer injectStatus;
    private Long hitCount;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private Long draftId;

    /** Convert to the api view record (defaults mirror column semantics). */
    public ProjectMemoryInfo toInfo() {
        return new ProjectMemoryInfo(id, projectId, content, sourceRef,
                injectStatus == null ? 0 : injectStatus,
                hitCount == null ? 0L : hitCount,
                createdAt == null ? null : createdAt.toLocalDateTime(),
                updatedAt == null ? null : updatedAt.toLocalDateTime(),
                draftId);
    }

    public Long getDraftId() {
        return draftId;
    }

    public void setDraftId(Long draftId) {
        this.draftId = draftId;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public Integer getInjectStatus() {
        return injectStatus;
    }

    public void setInjectStatus(Integer injectStatus) {
        this.injectStatus = injectStatus;
    }

    public Long getHitCount() {
        return hitCount;
    }

    public void setHitCount(Long hitCount) {
        this.hitCount = hitCount;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
