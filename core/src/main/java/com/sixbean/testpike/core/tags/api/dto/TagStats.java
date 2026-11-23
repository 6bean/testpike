package com.sixbean.testpike.core.tags.api.dto;


public record TagStats(long tagId, String name, int type, long caseCount,
                       long passCount, double passRate) {
}
