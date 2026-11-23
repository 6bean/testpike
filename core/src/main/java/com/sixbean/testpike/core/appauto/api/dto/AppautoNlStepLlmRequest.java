package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoNlStepLlmRequest(
        Long projectId,
        String engineCode,
        String description,
        List<String> allowedVerbs) {
}
