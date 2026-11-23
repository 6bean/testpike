package com.sixbean.testpike.core.tool.infrastructure.dataobject;

import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.tool.api.dto.ToolInvocationInfo;

import java.sql.Timestamp;


public class ToolInvocationRow {

    private Long id;
    private String caller;
    private Long actorId;
    private Long taskId;
    private Long toolId;
    private Long serverId;
    private String toolName;
    private Integer success;
    private Long durationMs;
    private String error;
    private String digestHash;
    private Timestamp createdAt;

    
    public ToolInvocationInfo toInfo() {
        return new ToolInvocationInfo(id, caller, actorId, taskId, toolId, serverId,
                toolName, success, durationMs, error, digestHash, ViewFormats.formatTs(createdAt));
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCaller() {
        return caller;
    }

    public void setCaller(String caller) {
        this.caller = caller;
    }

    public Long getActorId() {
        return actorId;
    }

    public void setActorId(Long actorId) {
        this.actorId = actorId;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public Long getToolId() {
        return toolId;
    }

    public void setToolId(Long toolId) {
        this.toolId = toolId;
    }

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Integer getSuccess() {
        return success;
    }

    public void setSuccess(Integer success) {
        this.success = success;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getDigestHash() {
        return digestHash;
    }

    public void setDigestHash(String digestHash) {
        this.digestHash = digestHash;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }
}
