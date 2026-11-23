package com.sixbean.testpike.core.audit.api.dto;


public record ChainVerifyOutcome(boolean valid, Long brokenAtId, long checkedCount) {

    public static ChainVerifyOutcome ok(long checkedCount) {
        return new ChainVerifyOutcome(true, null, checkedCount);
    }

    public static ChainVerifyOutcome broken(long brokenAtId, long checkedBefore) {
        return new ChainVerifyOutcome(false, brokenAtId, checkedBefore);
    }
}
