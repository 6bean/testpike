package com.sixbean.testpike.core.report.api.dto;


public record AiQualityView(long totalReviewed, long firstPassCount, double firstPassRate,
                            long modifyCount, double modifyRate,
                            long rejectCount, double rejectRate,
                            java.util.List<StatusCount> statusDist) {
}
