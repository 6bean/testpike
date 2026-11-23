package com.sixbean.testpike.core.judge.api.dto;


public record EvalSetVersionView(String setName, Integer version, String snapshotDigest,
                                 Integer sampleCount) {
}
