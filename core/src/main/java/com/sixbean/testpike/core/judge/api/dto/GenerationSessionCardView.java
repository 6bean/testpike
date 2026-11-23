package com.sixbean.testpike.core.judge.api.dto;


public record GenerationSessionCardView(Long runId, String targetType, String targetRef,
                                        String judgeModel, String finishedAt, Integer scored,
                                        Integer avgFinalScore, Integer passCount,
                                        Double passRate, Long tokensTotal,
                                        Long tokensPerSample, Double latencyAvgMs) {
}
