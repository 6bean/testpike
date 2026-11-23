package com.sixbean.testpike.core.mcp.infrastructure.dataobject;

import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.mcp.api.dto.McpServerInfo;
import com.sixbean.testpike.core.mcp.domain.component.McpServerHealth;
import com.sixbean.testpike.core.mcp.domain.component.McpTransport;

import java.sql.Timestamp;


public class McpServerRow {

    private Long id;
    private String name;
    private String endpoint;
    private Integer transport;
    private String authEnc;
    private String toolsJson;
    private Integer enabled;
    private Integer healthStatus;
    private String healthMessage;
    private Timestamp lastProbeAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;

 
    public McpServerInfo toInfo() {
        int transportCode = transport == null ? 0 : transport;
        int enabledCode = enabled == null ? 0 : enabled;
        int healthCode = healthStatus == null ? 0 : healthStatus;
        return new McpServerInfo(id, name, endpoint, transportCode,
                McpTransport.labelOf(transportCode),
                authEnc == null ? null : ConfigCipher.MASK,
                toolsJson, enabledCode, enabledCode == 1 ? "启用" : "停用",
                healthCode, McpServerHealth.labelOf(healthCode), healthMessage,
                ViewFormats.formatTs(lastProbeAt),
                ViewFormats.formatTs(createdAt), ViewFormats.formatTs(updatedAt));
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public Integer getTransport() {
        return transport;
    }

    public void setTransport(Integer transport) {
        this.transport = transport;
    }

    public String getAuthEnc() {
        return authEnc;
    }

    public void setAuthEnc(String authEnc) {
        this.authEnc = authEnc;
    }

    public String getToolsJson() {
        return toolsJson;
    }

    public void setToolsJson(String toolsJson) {
        this.toolsJson = toolsJson;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Integer getHealthStatus() {
        return healthStatus;
    }

    public void setHealthStatus(Integer healthStatus) {
        this.healthStatus = healthStatus;
    }

    public String getHealthMessage() {
        return healthMessage;
    }

    public void setHealthMessage(String healthMessage) {
        this.healthMessage = healthMessage;
    }

    public Timestamp getLastProbeAt() {
        return lastProbeAt;
    }

    public void setLastProbeAt(Timestamp lastProbeAt) {
        this.lastProbeAt = lastProbeAt;
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
