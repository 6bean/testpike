package com.sixbean.testpike.core.cases.api.dto;

/**
 * Request body for the POST flow-guide door (AO-9 stage 2). The HTTP tool
 * executor always dials exec_kind=1 endpoints with the model's raw input
 * JSON as the body, so the same read service needs a body-shaped entry
 * beside the GET query face. Only caseId is consumed (optional: absent =
 * whole status-vocabulary map).
 */
public record FlowGuideRequest(Long caseId) {
}
