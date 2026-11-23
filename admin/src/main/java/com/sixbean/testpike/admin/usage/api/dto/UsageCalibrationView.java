package com.sixbean.testpike.admin.usage.api.dto;


public record UsageCalibrationView(String model, String provider, long sampleCount,
                                   Double avgDeviationPct, Double p95AbsDeviationPct,
                                   Double avgEstTokens, Double avgUsageTokens,
                                   String lastSeenAt) {
}
