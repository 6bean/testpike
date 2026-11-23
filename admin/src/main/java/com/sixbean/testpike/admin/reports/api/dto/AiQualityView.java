package com.sixbean.testpike.admin.reports.api.dto;

import java.util.List;


public record AiQualityView(long totalReviewed, long firstPassCount, double firstPassRate,
                            long modifyCount, double modifyRate,
                            long rejectCount, double rejectRate,
                            List<StatusCount> statusDist) {
}
