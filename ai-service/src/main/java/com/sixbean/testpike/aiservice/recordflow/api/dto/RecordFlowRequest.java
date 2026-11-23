package com.sixbean.testpike.aiservice.recordflow.api.dto;

import java.util.List;
import java.util.Map;


public record RecordFlowRequest(
        Long projectId,
        String engineCode,
        String eventSource,
        List<Map<String, Object>> events,
        List<String> allowedVerbs) {
}
