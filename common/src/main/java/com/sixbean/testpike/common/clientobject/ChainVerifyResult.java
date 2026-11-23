package com.sixbean.testpike.common.clientobject;


public record ChainVerifyResult(
        boolean async,
        long taskId,
        int status,
        Boolean valid,
        Long brokenAtId,
        Long checkedCount,
        String checkedAt) {

    
    public static ChainVerifyResult syncDone(Boolean valid, Long brokenAtId, long checkedCount, String checkedAt) {
        return new ChainVerifyResult(false, 0, 2, valid, brokenAtId, checkedCount, checkedAt);
    }

    
    public static ChainVerifyResult submitted(long taskId) {
        return new ChainVerifyResult(true, taskId, 1, null, null, null, null);
    }

    
    public static ChainVerifyResult running(long taskId) {
        return new ChainVerifyResult(true, taskId, 1, null, null, null, null);
    }

    
    public static ChainVerifyResult failed(long taskId) {
        return new ChainVerifyResult(true, taskId, 3, null, null, null, null);
    }
}
