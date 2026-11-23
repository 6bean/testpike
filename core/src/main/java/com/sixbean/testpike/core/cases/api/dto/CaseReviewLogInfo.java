package com.sixbean.testpike.core.cases.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Use-case review history records are the O8 review-context sidebar data source for the 01-database-design tp_review_log.
 * <p>operatorName is the reviewer display name (fallback to username when display_name is empty, LEFT JOIN tp_user).
 *
 */
public record CaseReviewLogInfo(long id, long caseId, long userId, String operatorName,
                                String action, String remark, LocalDateTime createdAt,
                                List<String> feedbackTypes) {
}
