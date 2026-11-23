package com.sixbean.testpike.admin.cases.api.dto;


public record QuickExecuteRequest(Long envId, String browser) {

    
    public QuickExecuteRequest(Long envId) {
        this(envId, null);
    }
}
