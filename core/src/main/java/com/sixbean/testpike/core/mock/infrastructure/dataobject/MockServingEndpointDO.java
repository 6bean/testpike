package com.sixbean.testpike.core.mock.infrastructure.dataobject;

import java.time.LocalDateTime;

/**
 * Serving-inventory row (V240 read model): a stub joined with its owning
 * space plus the bounded-window hit count. Carries camelCase-mappable
 * properties for BeanPropertyRowMapper (records have no reflective no-arg
 * constructor, so the query maps through this DO and converts at the service
 * boundary like every other read here).
 */
public class MockServingEndpointDO {

    private Long stubId;
    private Long spaceId;
    private String spaceName;
    private String spaceKey;
    private Integer spaceStatus;
    private String basePathMode;
    private String ruleName;
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
    private Boolean enabled;
    private Integer version;
    private LocalDateTime updatedAt;
    private Long hits7d;

    public Long getStubId() {
        return stubId;
    }

    public void setStubId(Long stubId) {
        this.stubId = stubId;
    }

    public Long getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(Long spaceId) {
        this.spaceId = spaceId;
    }

    public String getSpaceName() {
        return spaceName;
    }

    public void setSpaceName(String spaceName) {
        this.spaceName = spaceName;
    }

    public String getSpaceKey() {
        return spaceKey;
    }

    public void setSpaceKey(String spaceKey) {
        this.spaceKey = spaceKey;
    }

    public Integer getSpaceStatus() {
        return spaceStatus;
    }

    public void setSpaceStatus(Integer spaceStatus) {
        this.spaceStatus = spaceStatus;
    }

    public String getBasePathMode() {
        return basePathMode;
    }

    public void setBasePathMode(String basePathMode) {
        this.basePathMode = basePathMode;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getHits7d() {
        return hits7d;
    }

    public void setHits7d(Long hits7d) {
        this.hits7d = hits7d;
    }
}
