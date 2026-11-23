package com.sixbean.testpike.core.plans.api.dto;


public record SkipItem(long caseId, String caseNo, String title, int status, String reason) {

    
    public SkipItem(long caseId, String caseNo, String title, int status) {
        this(caseId, caseNo, title, status, "状态不符");
    }
}
