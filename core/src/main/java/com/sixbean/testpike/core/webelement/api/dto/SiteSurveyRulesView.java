package com.sixbean.testpike.core.webelement.api.dto;

/**
 * Whole-site survey rules snapshot wire face : the
 * dispatch-time frozen snapshot text, viewport tiers merged when absent.
 *
 * @param rulesJson JSON text of the frozen rules snapshot
 */
public record SiteSurveyRulesView(String rulesJson) {
}
