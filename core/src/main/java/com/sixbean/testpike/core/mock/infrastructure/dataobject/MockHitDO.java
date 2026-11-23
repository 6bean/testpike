package com.sixbean.testpike.core.mock.infrastructure.dataobject;

import java.time.LocalDateTime;

/**
 * Data object for tp_mock_hit_log (inspector ledger row). The two jsonb columns
 * are read back via ::text aliases — codec work stays in the service layer
 * (same baseline as MockStubDO); this class never leaves the infrastructure
 * boundary.
 */
public class MockHitDO {

    private Long id;
    private Long spaceId;
    private Long stubId;
    private String requestSnapshot;
    private String responseSnapshot;
    private Boolean matched;
    private String matchTrace;
    private Integer latencyMs;
    private String faultApplied;
    private String sessionId;
    private LocalDateTime hitAt;
    private String source;

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

    public Long getStubId() {
        return stubId;
    }

    public void setStubId(Long stubId) {
        this.stubId = stubId;
    }

    public String getRequestSnapshot() {
        return requestSnapshot;
    }

    public void setRequestSnapshot(String requestSnapshot) {
        this.requestSnapshot = requestSnapshot;
    }

    public String getResponseSnapshot() {
        return responseSnapshot;
    }

    public void setResponseSnapshot(String responseSnapshot) {
        this.responseSnapshot = responseSnapshot;
    }

    public Boolean getMatched() {
        return matched;
    }

    public void setMatched(Boolean matched) {
        this.matched = matched;
    }

    public String getMatchTrace() {
        return matchTrace;
    }

    public void setMatchTrace(String matchTrace) {
        this.matchTrace = matchTrace;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getFaultApplied() {
        return faultApplied;
    }

    public void setFaultApplied(String faultApplied) {
        this.faultApplied = faultApplied;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public LocalDateTime getHitAt() {
        return hitAt;
    }

    public void setHitAt(LocalDateTime hitAt) {
        this.hitAt = hitAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
