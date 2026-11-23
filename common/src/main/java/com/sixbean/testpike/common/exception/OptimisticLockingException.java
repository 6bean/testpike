package com.sixbean.testpike.common.exception;

public class OptimisticLockingException extends RuntimeException {

    
    public static final int CODE = BizErrorCode.VERSION_CONFLICT;

    public OptimisticLockingException(String message) {
        super(message);
    }

    public OptimisticLockingException(String message, Throwable cause) {
        super(message, cause);
    }
}
