package com.sixbean.testpike.core.skill.infrastructure.dataobject;

import com.sixbean.testpike.core.skill.api.dto.SkillRecycleInfo;

import java.sql.Timestamp;


public class SkillRecycleRow {

    private Long id;
    private Long projectId;
    private String name;
    private Integer type;
    private Integer status;
    private Integer currentVersion;
    private Integer version;
    private Long deletedBy;
    private String deletedByName;
    private Timestamp deletedAt;

    
    public SkillRecycleInfo toInfo() {
        return new SkillRecycleInfo(id, projectId, name, type,
                status == null ? 0 : status,
                currentVersion == null ? 0 : currentVersion,
                version == null ? 0 : version,
                deletedBy, deletedByName,
                deletedAt == null ? null : deletedAt.toLocalDateTime());
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(Integer currentVersion) {
        this.currentVersion = currentVersion;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }

    public void setDeletedBy(Long deletedBy) {
        this.deletedBy = deletedBy;
    }

    public String getDeletedByName() {
        return deletedByName;
    }

    public void setDeletedByName(String deletedByName) {
        this.deletedByName = deletedByName;
    }

    public Timestamp getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Timestamp deletedAt) {
        this.deletedAt = deletedAt;
    }
}
