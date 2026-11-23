package com.sixbean.testpike.core.report.api.dto;


public record PersonalReviewView(long reviewedCount, long passCount, long needChangeCount,
                                 long rejectCount, double avgReviewSeconds) {
}
