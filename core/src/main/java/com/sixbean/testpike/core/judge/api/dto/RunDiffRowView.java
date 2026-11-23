package com.sixbean.testpike.core.judge.api.dto;


public record RunDiffRowView(Long sampleId, String inputPreview, Integer scoreA,
                             Integer scoreB, Integer delta, String verdict) {
}
