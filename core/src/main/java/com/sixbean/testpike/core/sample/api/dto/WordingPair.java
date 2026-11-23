package com.sixbean.testpike.core.sample.api.dto;


public record WordingPair(Long rejectSampleId, Long diffSampleId, String remark,
                          String diffChangesJson, String createdAt) {
}
