package com.sixbean.testpike.core.mock.infrastructure.dataobject;

import java.time.LocalDateTime;

/**
 * Data object for tp_mock_stub (one rule of the single rule source). The two
 * jsonb columns are read back via ::text aliases — codec work stays in the
 * service layer (same baseline as the cluster ReleaseManifest DO).
 */
public class MockStubDO {

    private Long id;
    private Long spaceId;
    private String name;
    private Integer priority;
    private String method;
    private String pathMatchType;
    private String pathPattern;
    private String matchersJson;
    private String responseJson;
    private String scenarioName;
    private String requiredState;
    private String newState;
    private String source;
    private String sourceRef;
    private String enrichedBy;
    private Boolean contractStale;
    private Boolean enabled;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPathMatchType() {
        return pathMatchType;
    }

    public void setPathMatchType(String pathMatchType) {
        this.pathMatchType = pathMatchType;
    }

    public String getPathPattern() {
        return pathPattern;
    }

    public void setPathPattern(String pathPattern) {
        this.pathPattern = pathPattern;
    }

    public String getMatchersJson() {
        return matchersJson;
    }

    public void setMatchersJson(String matchersJson) {
        this.matchersJson = matchersJson;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public void setResponseJson(String responseJson) {
        this.responseJson = responseJson;
    }

    public String getScenarioName() {
        return scenarioName;
    }

    public void setScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    public String getRequiredState() {
        return requiredState;
    }

    public void setRequiredState(String requiredState) {
        this.requiredState = requiredState;
    }

    public String getNewState() {
        return newState;
    }

    public void setNewState(String newState) {
        this.newState = newState;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getEnrichedBy() {
        return enrichedBy;
    }

    public void setEnrichedBy(String enrichedBy) {
        this.enrichedBy = enrichedBy;
    }

    public Boolean getContractStale() {
        return contractStale;
    }

    public void setContractStale(Boolean contractStale) {
        this.contractStale = contractStale;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
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
