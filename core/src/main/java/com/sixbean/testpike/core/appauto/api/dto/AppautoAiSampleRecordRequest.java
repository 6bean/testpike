package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoAiSampleRecordRequest(
        Long taskId,
        Long deviceId,
        Integer stepNo,
        String anchor,
        String hitSource,
        String reviewResult,
        Integer x,
        Integer y) {
}
