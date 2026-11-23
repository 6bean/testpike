package com.sixbean.testpike.core.judge.api.dto;


public record CalibrationGroupView(String rubricName, String rubricDomain, Long llmConfigId,
                                   int pairCount, double kappaPass, double spearman,
                                   boolean qualified, String note) {
}
