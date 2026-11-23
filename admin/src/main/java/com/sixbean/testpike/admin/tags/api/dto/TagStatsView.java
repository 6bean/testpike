package com.sixbean.testpike.admin.tags.api.dto;


public record TagStatsView(long tagId, String name, int type,
                           long caseCount, long passCount, double passRate) {
}
