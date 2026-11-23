package com.sixbean.testpike.core.judge.domain.component;


public record CalibrationParams(double kappaThreshold, int passThreshold, int bootstrapSamples,
                                double sentinelRate, double boostRate, int boundaryBand) {
}
