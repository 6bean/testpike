package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;


public record RecordFlowGenerateRequest(
        Long projectId,
        Long taskId,
        String engineCode,
        String eventSource,
        String title,
        List<java.util.Map<String, Object>> events) {
}
