package com.sixbean.testpike.core.delreq.infrastructure.dataobject;

import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestInfo;
import com.sixbean.testpike.common.approval.ApprovalStatus;

import java.sql.Timestamp;


public class DeleteRequestRow {

    private Long id;
    private Integer targetType;
    private Long targetId;
    private Long projectId;
    private String targetSummary;
    private String reason;
    private String rejectReason;
    private Long applicantId;
    private Integer status;
    private Long approverId;
    private Timestamp handledAt;
    private Timestamp createdAt;

    
    public DeleteRequestInfo toInfo() {
        return new DeleteRequestInfo(id, targetType, targetTypeLabel(targetType), targetId,
                projectId, targetSummary, reason, rejectReason, applicantId, status,
                statusLabel(status), approverId,
                handledAt == null ? null : handledAt.toLocalDateTime(),
                createdAt == null ? null : createdAt.toLocalDateTime());
    }

    
    public static String targetTypeLabel(Integer type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case 1 -> "用例";
            case 2 -> "脚本";
            case 3 -> "SKILL";
            default -> String.valueOf(type);
        };
    }

    
    public static String statusLabel(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case 2 -> "待审批";
            case 3 -> "已批准";
            case 4 -> "已拒绝";
            default -> ApprovalStatus.labelOf(status);
        };
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getTargetType() {
        return targetType;
    }

    public void setTargetType(Integer targetType) {
        this.targetType = targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getTargetSummary() {
        return targetSummary;
    }

    public void setTargetSummary(String targetSummary) {
        this.targetSummary = targetSummary;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Long getApplicantId() {
        return applicantId;
    }

    public void setApplicantId(Long applicantId) {
        this.applicantId = applicantId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Long getApproverId() {
        return approverId;
    }

    public void setApproverId(Long approverId) {
        this.approverId = approverId;
    }

    public Timestamp getHandledAt() {
        return handledAt;
    }

    public void setHandledAt(Timestamp handledAt) {
        this.handledAt = handledAt;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
