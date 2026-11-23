package com.sixbean.testpike.core.cases.api.dto;

/**
 * Excellent case marking request (M5 D3; contract 02 PUT /cases/{id}/excellent body
 * body {excellent, version}, following the CaseFlakyRequest shape).
 *
 * @param excellent true=mark false=cancel (null=validation rejected with 40001)
 * @param version optimistic lock version number (null=validation rejected with 40001)
 */
public record CaseExcellentRequest(Boolean excellent, Integer version) {
}
