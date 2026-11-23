package com.sixbean.testpike.core.delreq.api.dto;


public record DeleteRequestCreateInput(
        Long projectId,
        Integer targetType,
        Long targetId,
        String reason) {
}
