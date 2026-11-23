package com.sixbean.testpike.admin.appauto.api.dto;


public record EngineManageRequest(
        String name,
        int defaultPriority,
        String installDocUrl,
        int status) {
}
