package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;
import java.util.Map;


public record AppautoWxMockLlmRequest(
        Long projectId,
        String apiPath,
        String method,
        Map<String, Object> contract,
        List<String> genFunctions) {
}
