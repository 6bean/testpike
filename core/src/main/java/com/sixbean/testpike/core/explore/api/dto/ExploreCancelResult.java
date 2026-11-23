package com.sixbean.testpike.core.explore.api.dto;


public record ExploreCancelResult(long sessionId, String action) {

    
    public static final String ACTION_CANCELLED = "cancelled";
    
    public static final String ACTION_DELETED = "deleted";
}
