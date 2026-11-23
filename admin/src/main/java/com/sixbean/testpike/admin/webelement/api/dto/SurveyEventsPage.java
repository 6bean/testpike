package com.sixbean.testpike.admin.webelement.api.dto;

import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;

import java.util.List;

/**
 * Admin mirror of the core survey tail page (R62 segment c). {@code projectId}
 * is resolved server-side from the explore session row and consumed only by
 * the membership guard before the rows reach the browser; {@code events}
 * reuse the shared AiTaskEventView shape (same cursor replay contract as the
 * steps/extraction tails — no survey-specific viewer model).
 */
public record SurveyEventsPage(Long projectId, List<AiTaskEventView> events) {
}
