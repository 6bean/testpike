package com.sixbean.testpike.core.cases.api.dto;

/**
 * Test case step input parameters (01-database design tp_case_step).
 * <p>step_no is used only for input parameter validation (the server renumbers in list order from 1..n to avoid UNIQUE conflicts).
 * is_assert: 0=operation, 1=assertion.
 *
 * @param stepNo step sequence number (≥1, unique within the list; renumbered by the server)
 * @param description business-language description (required, ≤500)
 * @param isAssert 0 for operation and 1 for assertion
 * @param anchor step-level evidence anchor as jsonb object text (nullable; kinds
 *        elem/source/contract, normalized by the single-source CaseStepAnchorNormalizer)
 */
public record CaseStepInput(Integer stepNo, String description, Integer isAssert, String anchor) {

    /** Compatibility 3-arg constructor (no anchor evidence) — existing call sites untouched. */
    public CaseStepInput(Integer stepNo, String description, Integer isAssert) {
        this(stepNo, description, isAssert, null);
    }
}
