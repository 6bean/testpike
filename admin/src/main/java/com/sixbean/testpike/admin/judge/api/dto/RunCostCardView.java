package com.sixbean.testpike.admin.judge.api.dto;


public record RunCostCardView(Long runId, Long tokensTotal, Integer scored,
                              Integer sampleTotal, Double samplingRate,
                              Long tokensPerSample, boolean priceConfigured,
                              Double pricePer1kTokens, Double estimatedCost,
                              Double costPerSample, String note) {
}
