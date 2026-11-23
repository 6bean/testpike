package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoRecordConfirmRequest(
        List<Integer> checkedStepNos,
        Integer expectedScriptVersion) {
}
