package com.sixbean.testpike.common.exception;

public class ServiceException extends RuntimeException {

    private final Integer code;
    private final Object[] args;

    public ServiceException(String message) {
        this(message, null);
    }

    public ServiceException(String message, Integer code) {
        this(message, code, (Object[]) null);
    }

    public ServiceException(String message, Integer code, Object... args) {
        super(message);
        this.code = code;
        this.args = args;
    }

    public Integer getCode() {
        return code;
    }

    public Object[] getArgs() {
        return args;
    }

    
    @Override
    public String getMessage() {
        String raw = super.getMessage();
        if (raw == null || args == null || args.length == 0) {
            return raw;
        }
        try {
            return String.format(raw, args);
        } catch (Exception e) {
            return raw;
        }
    }
}
