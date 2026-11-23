package com.sixbean.testpike.core.cases.api.dto;

/**
 * Test case step information for detailed display, sorted by step_no in ascending order.
 *
 * @param id the step ID
 * @param stepNo the step sequence number
 * @param description the business-language description
 * @param isAssert 0 for operation and 1 for assertion
 * @param anchor step-level evidence anchor (jsonb object text, null when no anchor exists)
 */
public record CaseStepInfo(long id, int stepNo, String description, int isAssert, String anchor) {
}
