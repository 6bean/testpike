package com.sixbean.testpike.core.audit.domain.component;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


public final class OperatorContext {

    
    public static final String OPERATOR_ID_HEADER = "X-Operator-Id";

    private OperatorContext() {
    }

    
    public static Long currentOperatorId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            String header = attrs.getRequest().getHeader(OPERATOR_ID_HEADER);
            if (header != null && !header.isBlank()) {
                try {
                    return Long.valueOf(header.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
