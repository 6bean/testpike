package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoRecordDraftInput(
        Long taskId,
        String eventSource,
        String title,
        List<AppautoRecordDraftStepInput> steps) {
}
