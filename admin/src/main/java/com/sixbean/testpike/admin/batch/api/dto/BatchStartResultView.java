package com.sixbean.testpike.admin.batch.api.dto;

import com.sixbean.testpike.admin.plans.api.dto.SkipItemView;

import java.util.List;


public record BatchStartResultView(long batchId, int status, String statusKey, String statusLabel,
                                   Integer queuePos, String estimatedStartAt,
                                   List<SkipItemView> skipCases, String message) {
}
