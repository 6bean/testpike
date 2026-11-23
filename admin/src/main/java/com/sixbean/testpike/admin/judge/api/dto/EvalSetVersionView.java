package com.sixbean.testpike.admin.judge.api.dto;


public record EvalSetVersionView(String setName, Integer version, String snapshotDigest,
                                 Integer sampleCount) {
}
