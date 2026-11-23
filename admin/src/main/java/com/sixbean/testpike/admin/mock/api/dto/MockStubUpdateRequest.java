package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Stub update request (admin face) — same payload shape as the create request
 * plus the optimistic-lock echo: the editor submits the {@code expectedVersion}
 * of the row it actually read, and a stale echo (someone saved in between)
 * comes back as the plain-word version conflict, never a silent overwrite.
 * Provenance columns (source, sourceRef) are not carried — birth facts are
 * immutable through the edit door, core keeps the stored values.
 */
public record MockStubUpdateRequest(
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
        Boolean enabled,
        Integer expectedVersion) {

    /** Bridge-side shape pass-through onto the core input record (no reprocessing). */
    public MockStubInput toInput() {
        return new MockStubInput(name, priority, method, pathMatchType, pathPattern,
                matchersJson, responseJson, scenarioName, requiredState, newState,
                null, null, enabled, expectedVersion);
    }
}
