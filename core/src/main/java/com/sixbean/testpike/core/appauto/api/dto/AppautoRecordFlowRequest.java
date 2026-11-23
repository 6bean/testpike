package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;
import java.util.Map;


public record AppautoRecordFlowRequest(
        Long taskId,
        String engineCode,
        String eventSource,
        String title,
        List<Map<String, Object>> events) {
}
