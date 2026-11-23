package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.Map;


public record WxMockGenerateRequest(
        Long projectId,
        String apiPath,
        String method,
        Map<String, Object> contract) {
}
