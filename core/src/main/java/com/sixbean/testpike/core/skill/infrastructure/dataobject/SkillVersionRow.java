package com.sixbean.testpike.core.skill.infrastructure.dataobject;

import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.skill.api.dto.SkillVersionDetail;
import com.sixbean.testpike.core.skill.api.dto.SkillVersionInfo;

import java.sql.Timestamp;


public class SkillVersionRow {

    private Long skillId;
    private Integer version;
    private String body;
    private String frontmatterJson;
    private Integer statusAtSnapshot;
    private String reason;
    private Timestamp approvedAt;
    private Long createdBy;
    private Timestamp createdAt;

    
    public SkillVersionInfo toInfo() {
        int statusCode = statusAtSnapshot == null ? 0 : statusAtSnapshot;
        return new SkillVersionInfo(skillId, version == null ? 0 : version, reason,
                statusCode, ApprovalStatus.labelOf(statusCode),
                ViewFormats.formatTs(approvedAt), createdBy,
                ViewFormats.formatTs(createdAt), ViewFormats.preview(body));
    }

    
    public SkillVersionDetail toDetail() {
        int statusCode = statusAtSnapshot == null ? 0 : statusAtSnapshot;
        return new SkillVersionDetail(skillId, version == null ? 0 : version, body,
                frontmatterJson, reason, statusCode, ApprovalStatus.labelOf(statusCode),
                ViewFormats.formatTs(approvedAt), createdBy, ViewFormats.formatTs(createdAt));
    }

    public Long getSkillId() {
        return skillId;
    }

    public void setSkillId(Long skillId) {
        this.skillId = skillId;
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

    public Integer getStatusAtSnapshot() {
        return statusAtSnapshot;
    }

    public void setStatusAtSnapshot(Integer statusAtSnapshot) {
        this.statusAtSnapshot = statusAtSnapshot;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Timestamp getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Timestamp approvedAt) {
        this.approvedAt = approvedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
