package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;
import java.util.Map;


public record AppautoRecordFlowLlmRequest(
        Long projectId,
        String engineCode,
        String eventSource,
        List<Map<String, Object>> events,
        List<String> allowedVerbs) {
}
