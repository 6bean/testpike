package com.sixbean.testpike.aiservice.wxmock.api.dto;

import java.util.List;
import java.util.Map;


public record WxMockRequest(
        Long projectId,
        String apiPath,
        String method,
        Map<String, Object> contract,
        List<String> genFunctions) {
}
