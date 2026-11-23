package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoNlStepRequest(
        Long projectId,
        String engineCode,
        String description) {
}
