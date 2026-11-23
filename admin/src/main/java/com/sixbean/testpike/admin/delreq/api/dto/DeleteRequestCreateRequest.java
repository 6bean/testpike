package com.sixbean.testpike.admin.delreq.api.dto;


public record DeleteRequestCreateRequest(Long projectId, Integer targetType, Long targetId, String reason) {
}
