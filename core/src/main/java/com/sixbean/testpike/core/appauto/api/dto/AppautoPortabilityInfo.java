package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoPortabilityInfo(
        String engineCode,
        String engineName,
        long caseId,
        String caseTitle,
        String verdict,
        List<String> items,
        int stepCount) {

    
    public static final String RUNNABLE = "RUNNABLE";
    public static final String DEGRADED = "DEGRADED";
    public static final String REWRITE = "REWRITE";
}
