package com.sixbean.testpike.core.mcp.infrastructure.dataobject;

import com.sixbean.testpike.core.common.domain.component.ViewFormats;

import java.sql.Timestamp;

/**
 * tp_mcp_knowledge_sync row object (P7 data access baseline:
 * NamedParameterJdbcTemplate + BeanPropertyRowMapper).
 *
 * <p>Mutability only satisfies the mapper's no-arg + setter reflection
 * requirement; anything leaving the infrastructure layer goes through
 * {@link #toInfo(String)} so the raw JSONB text never reaches the api face.
 */
public class McpKnowledgeSyncRow {

    private Long id;
    private Long projectId;
    private Long serverId;
    private String name;
    private String toolName;
    private String paramMapping;
    private Integer syncIntervalMinutes;
    private Long targetProjectId;
    private Long targetPackId;
    private Boolean autoActivate;
    private Boolean enabled;
    private String lastStatus;
    private String lastError;
    private Timestamp lastSyncAt;
    private Integer lastNewCount;
    private Integer lastSkippedCount;
    private Integer consecutiveFailures;
    private Long createdBy;

    /** Parse the JSONB text once here; the api view carries a typed map. */
    public com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncInfo toInfo(String serverName) {
        return new com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncInfo(
                id, projectId, serverId, serverName, name, toolName,
                parseMapping(paramMapping), syncIntervalMinutes, targetProjectId, targetPackId,
                Boolean.TRUE.equals(autoActivate), Boolean.TRUE.equals(enabled),
                lastStatus, lastError, ViewFormats.formatTs(lastSyncAt),
                lastNewCount, lastSkippedCount, consecutiveFailures);
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> parseMapping(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, java.util.LinkedHashMap.class);
        } catch (Exception e) {
            return java.util.Map.of();
        }
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

    public Long getServerId() {
        return serverId;
    }

    public void setServerId(Long serverId) {
        this.serverId = serverId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getParamMapping() {
        return paramMapping;
    }

    public void setParamMapping(String paramMapping) {
        this.paramMapping = paramMapping;
    }

    public Integer getSyncIntervalMinutes() {
        return syncIntervalMinutes;
    }

    public void setSyncIntervalMinutes(Integer syncIntervalMinutes) {
        this.syncIntervalMinutes = syncIntervalMinutes;
    }

    public Long getTargetProjectId() {
        return targetProjectId;
    }

    public void setTargetProjectId(Long targetProjectId) {
        this.targetProjectId = targetProjectId;
    }

    public Long getTargetPackId() {
        return targetPackId;
    }

    public void setTargetPackId(Long targetPackId) {
        this.targetPackId = targetPackId;
    }

    public Boolean getAutoActivate() {
        return autoActivate;
    }

    public void setAutoActivate(Boolean autoActivate) {
        this.autoActivate = autoActivate;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
        this.lastStatus = lastStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Timestamp getLastSyncAt() {
        return lastSyncAt;
    }

    public void setLastSyncAt(Timestamp lastSyncAt) {
        this.lastSyncAt = lastSyncAt;
    }

    public Integer getLastNewCount() {
        return lastNewCount;
    }

    public void setLastNewCount(Integer lastNewCount) {
        this.lastNewCount = lastNewCount;
    }

    public Integer getLastSkippedCount() {
        return lastSkippedCount;
    }

    public void setLastSkippedCount(Integer lastSkippedCount) {
        this.lastSkippedCount = lastSkippedCount;
    }

    public Integer getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(Integer consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }
}
