package com.sixbean.testpike.admin.judge.api.dto;


public record GenerationSessionCardView(Long runId, String targetType, String targetRef,
                                        String judgeModel, String finishedAt, Integer scored,
                                        Integer avgFinalScore, Integer passCount,
                                        Double passRate, Long tokensTotal,
                                        Long tokensPerSample, Double latencyAvgMs) {
}
