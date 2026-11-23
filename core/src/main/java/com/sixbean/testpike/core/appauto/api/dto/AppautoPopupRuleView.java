package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoPopupRuleView(
        long id,
        Long projectId,
        Long sourceTaskId,
        String title,
        String rule,
        int status,
        String source,
        String createdAt) {
}
