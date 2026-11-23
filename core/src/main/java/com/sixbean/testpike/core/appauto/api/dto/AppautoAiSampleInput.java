package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoAiSampleInput(
        Long projectId,
        Long caseId,
        Long deviceId,
        Integer stepNo,
        String anchor,
        String hitSource,
        String reviewResult,
        Integer x,
        Integer y,
        Long sourceTaskId) {
}
