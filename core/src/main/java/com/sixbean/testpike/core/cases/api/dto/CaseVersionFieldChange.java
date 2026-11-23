package com.sixbean.testpike.core.cases.api.dto;

/**
 * Version diff single-field change (field-level before/after).
 * The <p> field semantic keys are: title/precondition/expectedResult/priority/confidence/endType/phase/status/.
 * assigneeId/steps/script); label is the Chinese display name (shown directly in frontend column headers and highlighted in plain language).
 *
 */
public record CaseVersionFieldChange(String field, String label, Object before, Object after) {
}
