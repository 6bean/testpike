package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoAiSampleCacheHitRequest(
        Long taskId,
        Long deviceId,
        Integer stepNo,
        String anchor) {
}
