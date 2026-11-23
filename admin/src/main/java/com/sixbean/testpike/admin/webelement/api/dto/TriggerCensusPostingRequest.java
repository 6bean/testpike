package com.sixbean.testpike.admin.webelement.api.dto;

/**
 * Census-posting trigger body : the concrete project anchor
 * only. The manage gate runs against this anchor before the internal door
 * is reached, so a missing top-bar selection refuses here instead of
 * firing a projectless scan.
 */
public record TriggerCensusPostingRequest(Long projectId) {
}
