package com.sixbean.testpike.aiservice.nlstep.api.dto;

import java.util.List;


public record NlStepRequest(
        Long projectId,
        String engineCode,
        String description,
        List<String> allowedVerbs) {
}
