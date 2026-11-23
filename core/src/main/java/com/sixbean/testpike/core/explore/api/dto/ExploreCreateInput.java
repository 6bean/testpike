package com.sixbean.testpike.core.explore.api.dto;


public record ExploreCreateInput(String goalText, String startUrl, Long envId, String credRef,
                                 String loginStateName) {

 
    public ExploreCreateInput(String goalText, String startUrl, Long envId, String credRef) {
        this(goalText, startUrl, envId, credRef, null);
    }

 
    public ExploreCreateInput(String goalText, String startUrl, Long envId) {
        this(goalText, startUrl, envId, null, null);
    }

 
    public ExploreCreateInput(String goalText, String startUrl) {
        this(goalText, startUrl, null, null, null);
    }
}
