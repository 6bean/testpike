package com.sixbean.testpike.core.mock.infrastructure.dataobject;

import java.time.LocalDateTime;

/**
 * Data object for tp_mock_space (the routing container of one callable mock
 * endpoint group). jsonb-free table; read paths alias columns to these field
 * names for BeanPropertyRowMapper. DO never leaks past the api layer.
 */
public class MockSpaceDO {

    private Long id;
    private Long projectId;
    private String name;
    private String spaceKey;
    private String alias;
    private Long envId;
    private String basePathMode;
    private Integer status;
    private String authMode;
    private String proxyTargetUrl;
    private Integer rateLimitRps;
    private Long quotaDaily;
    private Integer priority;
    private Integer chaosPercent;
    private String missPolicy;
    private LocalDateTime expiresAt;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public String getSpaceKey() {
        return spaceKey;
    }

    public void setSpaceKey(String spaceKey) {
        this.spaceKey = spaceKey;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public Long getEnvId() {
        return envId;
    }

    public void setEnvId(Long envId) {
        this.envId = envId;
    }

    public String getBasePathMode() {
        return basePathMode;
    }

    public void setBasePathMode(String basePathMode) {
        this.basePathMode = basePathMode;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getAuthMode() {
        return authMode;
    }

    public void setAuthMode(String authMode) {
        this.authMode = authMode;
    }

    public String getProxyTargetUrl() {
        return proxyTargetUrl;
    }

    public void setProxyTargetUrl(String proxyTargetUrl) {
        this.proxyTargetUrl = proxyTargetUrl;
    }

    public Integer getRateLimitRps() {
        return rateLimitRps;
    }

    public void setRateLimitRps(Integer rateLimitRps) {
        this.rateLimitRps = rateLimitRps;
    }

    public Long getQuotaDaily() {
        return quotaDaily;
    }

    public void setQuotaDaily(Long quotaDaily) {
        this.quotaDaily = quotaDaily;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public Integer getChaosPercent() {
        return chaosPercent;
    }

    public void setChaosPercent(Integer chaosPercent) {
        this.chaosPercent = chaosPercent;
    }

    public String getMissPolicy() {
        return missPolicy;
    }

    public void setMissPolicy(String missPolicy) {
        this.missPolicy = missPolicy;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
