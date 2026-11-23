package com.sixbean.testpike.admin.appauto.api.dto;


public record NlStepGenerateRequest(
        Long projectId,
        String engineCode,
        String description) {
}
