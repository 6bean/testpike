package com.sixbean.testpike.core.cases.api.dto;

/**
 * Single assignment request (02-Interface Design 3.6 POST /cases/{id}/assign).
 *
 * @param assigneeId target member for assignment (project member, non-empty; empty returns 40001).
 */
public record AssignRequest(Long assigneeId) {
}
