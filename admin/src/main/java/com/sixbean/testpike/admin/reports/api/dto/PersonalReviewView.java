package com.sixbean.testpike.admin.reports.api.dto;


public record PersonalReviewView(long reviewedCount, long passCount, long needChangeCount,
                                 long rejectCount, double avgReviewSeconds) {
}
