package com.sixbean.testpike.admin.appauto.api.dto;


public record AppautoRecordDraftView(
        long id,
        Long projectId,
        Long caseId,
        Long sourceTaskId,
        String title,
        String eventSource,
        String items,
        int status,
        Integer scriptVersion,
        String createdAt) {
}
