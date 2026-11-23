package com.sixbean.testpike.core.webelement.api.dto;

/**
 * Acknowledgement of one dispatched page survey: the explore session id the
 * candidate pool will land under, plus the surveyed url echoed back so the
 * page can name the object in its success feedback.
 */
public record SurveyResultView(Long sessionId, String startUrl) {
}
