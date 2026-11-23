package com.sixbean.testpike.core.monitor.infrastructure.dataobject;

import java.time.LocalDateTime;


public class MonitorTarget {

    private Long id;
    private Long projectId;
    private Long envId;
    private String name;
    private String targetType;
    private String probeConfig;
    private Integer checkIntervalSeconds;
    private boolean enabled;
    private LocalDateTime silenceUntil;
    private String silenceReason;
    private String lastState;
    private LocalDateTime lastCheckAt;
    private String lastCause;
    private LocalDateTime nextCheckAt;
    private int backoffLevel;
    private int stableCycles;
    private int consecutiveFailures;
    private int consecutiveSuccesses;
    private int envVersion;
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

    public Long getEnvId() {
        return envId;
    }

    public void setEnvId(Long envId) {
        this.envId = envId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getProbeConfig() {
        return probeConfig;
    }

    public void setProbeConfig(String probeConfig) {
        this.probeConfig = probeConfig;
    }

    public Integer getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    public void setCheckIntervalSeconds(Integer checkIntervalSeconds) {
        this.checkIntervalSeconds = checkIntervalSeconds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getSilenceUntil() {
        return silenceUntil;
    }

    public void setSilenceUntil(LocalDateTime silenceUntil) {
        this.silenceUntil = silenceUntil;
    }

    public String getSilenceReason() {
        return silenceReason;
    }

    public void setSilenceReason(String silenceReason) {
        this.silenceReason = silenceReason;
    }

    public String getLastState() {
        return lastState;
    }

    public void setLastState(String lastState) {
        this.lastState = lastState;
    }

    public LocalDateTime getLastCheckAt() {
        return lastCheckAt;
    }

    public void setLastCheckAt(LocalDateTime lastCheckAt) {
        this.lastCheckAt = lastCheckAt;
    }

    public String getLastCause() {
        return lastCause;
    }

    public void setLastCause(String lastCause) {
        this.lastCause = lastCause;
    }

    public LocalDateTime getNextCheckAt() {
        return nextCheckAt;
    }

    public void setNextCheckAt(LocalDateTime nextCheckAt) {
        this.nextCheckAt = nextCheckAt;
    }

    public int getBackoffLevel() {
        return backoffLevel;
    }

    public void setBackoffLevel(int backoffLevel) {
        this.backoffLevel = backoffLevel;
    }

    public int getStableCycles() {
        return stableCycles;
    }

    public void setStableCycles(int stableCycles) {
        this.stableCycles = stableCycles;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public int getConsecutiveSuccesses() {
        return consecutiveSuccesses;
    }

    public void setConsecutiveSuccesses(int consecutiveSuccesses) {
        this.consecutiveSuccesses = consecutiveSuccesses;
    }

    public int getEnvVersion() {
        return envVersion;
    }

    public void setEnvVersion(int envVersion) {
        this.envVersion = envVersion;
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
