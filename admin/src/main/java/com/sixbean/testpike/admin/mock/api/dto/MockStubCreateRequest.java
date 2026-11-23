package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Stub create request (admin face) — same thirteen fields as the save input;
 * the owning space id arrives as the path variable, so this record carries
 * only the rule payload. State columns are validated against the bound
 * scenario by core at the save-chain single point (never re-decided here).
 */
public record MockStubCreateRequest(
        String name,
        Integer priority,
        String method,
        String pathMatchType,
        String pathPattern,
        String matchersJson,
        String responseJson,
        String scenarioName,
        String requiredState,
        String newState,
        String source,
        String sourceRef,
        Boolean enabled) {

    /** Bridge-side shape pass-through onto the core input record (no reprocessing). */
    public MockStubInput toInput() {
        return new MockStubInput(name, priority, method, pathMatchType, pathPattern,
                matchersJson, responseJson, scenarioName, requiredState, newState,
                source, sourceRef, enabled);
    }
}

