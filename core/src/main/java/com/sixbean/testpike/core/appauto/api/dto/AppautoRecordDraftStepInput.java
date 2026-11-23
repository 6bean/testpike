package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoRecordDraftStepInput(
        Integer stepNo,
        String action,
        String target,
        String value,
        String reason) {
}
