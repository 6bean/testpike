package com.sixbean.testpike.core.usage.api.dto;


public record UsageCalibrationInfo(String model, String provider, long sampleCount,
                                   Double avgDeviationPct, Double p95AbsDeviationPct,
                                   Double avgEstTokens, Double avgUsageTokens,
                                   String lastSeenAt) {
}
