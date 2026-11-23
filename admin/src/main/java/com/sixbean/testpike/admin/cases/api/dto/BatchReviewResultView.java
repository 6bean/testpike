package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;


public record BatchReviewResultView(List<Long> succeeded, List<SkippedReviewItemView> skipped) {
}
