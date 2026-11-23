package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Scenario create request (admin face) — the owning space id arrives as the
 * path variable, so this record carries only the declaration payload. All
 * machine-shape gates (blank name, over-long name, duplicate name, broken
 * declaration) fire in core at the save-chain single point.
 */
public record MockScenarioCreateRequest(
        String name,
        String definitionJson) {

    /** Bridge-side shape pass-through onto the core input record (no reprocessing). */
    public MockScenarioInput toInput() {
        return new MockScenarioInput(name, definitionJson);
    }
}
