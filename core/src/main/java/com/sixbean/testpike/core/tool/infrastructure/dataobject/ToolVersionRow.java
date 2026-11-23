package com.sixbean.testpike.core.tool.infrastructure.dataobject;

import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.tool.api.dto.ToolVersionDetail;
import com.sixbean.testpike.core.tool.api.dto.ToolVersionInfo;

import java.sql.Timestamp;


public class ToolVersionRow {

    private Long toolId;
    private Integer version;
    private String body;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String execSpecJson;
    private Integer timeoutMs;
    private Integer statusAtSnapshot;
    private String reason;
    private Long createdBy;
    private Timestamp createdAt;

    
    public ToolVersionInfo toInfo() {
        int statusCode = statusAtSnapshot == null ? 0 : statusAtSnapshot;
        return new ToolVersionInfo(toolId, version == null ? 0 : version, reason,
                statusCode, ApprovalStatus.labelOf(statusCode), createdBy,
                ViewFormats.formatTs(createdAt), ViewFormats.preview(body));
    }

    
    public ToolVersionDetail toDetail() {
        int statusCode = statusAtSnapshot == null ? 0 : statusAtSnapshot;
        return new ToolVersionDetail(toolId, version == null ? 0 : version, body,
                inputSchemaJson, outputSchemaJson, execSpecJson, timeoutMs,
                statusCode, ApprovalStatus.labelOf(statusCode), reason, createdBy,
                ViewFormats.formatTs(createdAt));
    }

    public Long getToolId() {
        return toolId;
    }

    public void setToolId(Long toolId) {
        this.toolId = toolId;
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
