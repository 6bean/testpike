package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;


public record BatchReviewRequest(String action, List<Long> caseIds, String remark,
                                 Boolean confirmLowConfidence, List<String> feedbackTypes) {
}
