package com.sixbean.testpike.admin.ai.api.dto;


public record AiBatchEstimateView(long count, long estimatedTokens, double estimatedCostYuan, int maxCount,
                                  Integer scenarioCount, Integer deliberationRounds) {
}
