package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoStabilityView(long caseId, Long deviceId, int samples, int flips,
                                   boolean flaky, String reason, double stabilityScore) {
}
