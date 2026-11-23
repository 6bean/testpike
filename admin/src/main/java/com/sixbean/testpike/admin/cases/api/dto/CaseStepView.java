package com.sixbean.testpike.admin.cases.api.dto;

/**
 *
 * @param anchor step-level evidence anchor as stored jsonb text (null = none);
 * mirrors core CaseStepInfo one-for-one so the case detail page
 * can show where each step came from
 */
public record CaseStepView(long id, int stepNo, String description, int isAssert, String anchor) {
}
