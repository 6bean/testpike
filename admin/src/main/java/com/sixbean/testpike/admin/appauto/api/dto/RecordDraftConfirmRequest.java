package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;


public record RecordDraftConfirmRequest(
        List<Integer> checkedStepNos,
        Integer expectedScriptVersion) {
}
