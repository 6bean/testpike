package com.sixbean.testpike.core.webelement.api.dto;

/**
 * Manual survey trigger payload (WE-S4 segment P4). The page rides as a full
 * URL; the shared dispatch component folds it into the survey goal marker
 * plus declared viewport tiers — this DTO carries nothing else so the door
 * cannot smuggle a hand-built goal text past the single source.
 */
public record SurveyTriggerInput(Long projectId, String startUrl) {
}
