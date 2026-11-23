package com.sixbean.testpike.core.webelement.api.dto;

import com.sixbean.testpike.core.events.api.dto.TaskEventRow;

import java.util.List;

/**
 * One window of the survey process tail (R62 segment c). {@code projectId} is
 * resolved server-side from the explore session row so the admin bridge can
 * guard membership before the events reach the browser; {@code events} are
 * the shared ledger rows (cursor = strictly increasing row id, same replay
 * contract as the extraction/steps tails — no survey-specific reader).
 */
public record SurveyEventsPage(Long projectId, List<TaskEventRow> events) {
}
