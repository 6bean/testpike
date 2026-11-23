package com.sixbean.testpike.admin.cases.api.dto;

import java.time.LocalDateTime;
import java.util.List;


public record CaseReviewLogView(Long id, Long caseId, Long userId, String operatorName,
                                String action, String remark, LocalDateTime createdAt,
                                List<String> feedbackTypes) {
}
