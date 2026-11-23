package com.sixbean.testpike.admin.webelement.api.dto;

/**
 * Survey trigger body on the user-facing door (WE-S4 segment P4). projectId is
 * the project lens the caller selected in the header; the admin side treats
 * it as the routing value for the manage gate and forwards it to core.
 */
public record TriggerSurveyRequest(Long projectId, String startUrl) {
}
