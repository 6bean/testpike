package com.sixbean.testpike.core.tool.infrastructure.dataobject;

import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.tool.api.dto.ToolDetail;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.domain.component.ToolExecKind;
import com.sixbean.testpike.core.tool.domain.component.ToolType;

import java.sql.Timestamp;


public class ToolRow {

    private Long id;
    private Long projectId;
    private String name;
    private Integer type;
    private String category;
    private String description;
    private String body;
    private String tagsJson;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private Integer execKind;
    private String execSpecJson;
    private Integer timeoutMs;
    private Integer status;
    private Integer currentVersion;
    private Integer version;
    private Long createdBy;
    private Long approvedBy;
    private Timestamp approvedAt;
    private String rejectReason;
    private Timestamp createdAt;
    private Timestamp updatedAt;

 
    public ToolInfo toInfo() {
        int typeCode = type == null ? 0 : type;
        int statusCode = status == null ? 0 : status;
        return new ToolInfo(id, projectId, name, typeCode, ToolType.labelOf(typeCode),
                category, description, ViewFormats.preview(body), tagsJson,
                inputSchemaJson, outputSchemaJson, execKind,
                execKind == null ? null : ToolExecKind.labelOf(execKind),
                execSpecJson, timeoutMs == null ? 0 : timeoutMs,
                statusCode, ApprovalStatus.labelOf(statusCode),
                currentVersion == null ? 0 : currentVersion, version == null ? 0 : version,
                createdBy, approvedBy, ViewFormats.formatTs(approvedAt), rejectReason,
                ViewFormats.formatTs(createdAt), ViewFormats.formatTs(updatedAt));
    }

 
    public ToolDetail toDetail() {
        int typeCode = type == null ? 0 : type;
        int statusCode = status == null ? 0 : status;
        return new ToolDetail(id, projectId, name, typeCode, ToolType.labelOf(typeCode),
                category, description, body, tagsJson,
                inputSchemaJson, outputSchemaJson, execKind,
                execKind == null ? null : ToolExecKind.labelOf(execKind),
                execSpecJson, timeoutMs == null ? 0 : timeoutMs,
                statusCode, ApprovalStatus.labelOf(statusCode),
                currentVersion == null ? 0 : currentVersion, version == null ? 0 : version,
                createdBy, approvedBy, ViewFormats.formatTs(approvedAt), rejectReason,
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

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getTagsJson() {
        return tagsJson;
    }

    public void setTagsJson(String tagsJson) {
        this.tagsJson = tagsJson;
    }

    public String getInputSchemaJson() {
        return inputSchemaJson;
    }

    public void setInputSchemaJson(String inputSchemaJson) {
        this.inputSchemaJson = inputSchemaJson;
    }

    public String getOutputSchemaJson() {
        return outputSchemaJson;
    }

    public void setOutputSchemaJson(String outputSchemaJson) {
        this.outputSchemaJson = outputSchemaJson;
    }

    public Integer getExecKind() {
        return execKind;
    }

    public void setExecKind(Integer execKind) {
        this.execKind = execKind;
    }

    public String getExecSpecJson() {
        return execSpecJson;
    }

    public void setExecSpecJson(String execSpecJson) {
        this.execSpecJson = execSpecJson;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
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
