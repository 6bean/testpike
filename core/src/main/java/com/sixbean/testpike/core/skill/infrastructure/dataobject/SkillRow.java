package com.sixbean.testpike.core.skill.infrastructure.dataobject;

import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import com.sixbean.testpike.core.skill.domain.component.SkillType;

import java.sql.Timestamp;


public class SkillRow {

    private Long id;
    private Long projectId;
    private String name;
    private Integer type;
    private Integer status;
    private Integer currentVersion;
    private Integer version;
    private String body;
    private String frontmatterJson;
    private Long createdBy;
    private Long approvedBy;
    private Timestamp approvedAt;
    private String rejectReason;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private Integer deleted;

 
    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }

 
    public SkillInfo toInfo() {
        int typeCode = type == null ? 0 : type;
        int statusCode = status == null ? 0 : status;
        return new SkillInfo(id, projectId, name, typeCode, SkillType.labelOf(typeCode),
                statusCode, ApprovalStatus.labelOf(statusCode),
                currentVersion == null ? 0 : currentVersion, version == null ? 0 : version,
                ViewFormats.preview(body), frontmatterJson, createdBy, approvedBy,
                ViewFormats.formatTs(approvedAt), rejectReason,
                ViewFormats.formatTs(createdAt), ViewFormats.formatTs(updatedAt));
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

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getFrontmatterJson() {
        return frontmatterJson;
    }

    public void setFrontmatterJson(String frontmatterJson) {
        this.frontmatterJson = frontmatterJson;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(Long approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Timestamp getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Timestamp approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
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
