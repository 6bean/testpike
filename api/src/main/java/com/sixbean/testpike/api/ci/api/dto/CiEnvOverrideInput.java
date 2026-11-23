package com.sixbean.testpike.api.ci.api.dto;


public record CiEnvOverrideInput(String url, String accountRef) {

    
    public boolean hasAny() {
        return url != null || accountRef != null;
    }
}
