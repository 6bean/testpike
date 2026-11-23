package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoEngineUpdateInput(
        String name,
        int defaultPriority,
        String installDocUrl,
        int status) {
}
