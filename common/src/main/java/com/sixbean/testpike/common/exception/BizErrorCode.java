package com.sixbean.testpike.common.exception;


public final class BizErrorCode {

    
    public static final int VALIDATION_ERROR = 40001;

 
    public static final int COST_BUDGET_EXCEEDED = 40002;

    
    public static final int UNAUTHORIZED = 40101;

    
    public static final int FORBIDDEN = 40301;

    
    public static final int LOCKED = 42301;

    
    public static final int NOT_FOUND = 40401;

 
    public static final int METHOD_NOT_ALLOWED = 40501;

    
    public static final int VERSION_CONFLICT = 40901;

    
    public static final int STATE_MACHINE_REJECT = 40902;

    
    public static final int IDEMPOTENT_DUPLICATE = 40903;

    
    public static final int RESOURCE_CONFLICT = 40904;

 
    public static final int SCRIPT_LIMIT_EXCEEDED = 40905;

    
    public static final int AI_PROVIDER_ERROR = 50201;

    
    public static final int AI_TIMEOUT = 50401;

    
    public static final int AI_FORMAT_ERROR = 50003;

 
    public static final int SERVICE_UNAVAILABLE = 50301;

 
    public static final int PASSWORD_EXPIRED = 42801;

 
    public static final int AI_PROMPT_TOO_LARGE = 41301;

 
    public static final int RATE_LIMITED = 42901;

    
    public static final int INTERNAL_ERROR = 50000;

    private BizErrorCode() {
    }
}
