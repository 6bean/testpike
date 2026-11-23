package com.sixbean.testpike.core.appauto.api.dto;

import java.util.Map;


public record AppautoWxMockRequest(
        Long projectId,
        String apiPath,
        String method,
        Map<String, Object> contract) {
}
