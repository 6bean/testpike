package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;


public record AppautoPortabilityView(
        String engineCode,
        String engineName,
        long caseId,
        String caseTitle,
        String verdict,
        List<String> items,
        int stepCount) {
}
