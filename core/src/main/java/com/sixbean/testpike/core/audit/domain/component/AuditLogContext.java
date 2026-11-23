package com.sixbean.testpike.core.audit.domain.component;

import java.util.Map;


public final class AuditLogContext {

    private static final ThreadLocal<Map<String, Object>> BEFORE = new ThreadLocal<>();

    /** ThreadLocal bridge for outcome snapshots assembled inside method bodies (mirrors BEFORE). */
    private static final ThreadLocal<Map<String, Object>> AFTER = new ThreadLocal<>();

    private AuditLogContext() {
    }

    
    public static void setBefore(Map<String, Object> before) {
        BEFORE.set(before);
    }

    
    public static Map<String, Object> takeBefore() {
        Map<String, Object> value = BEFORE.get();
        BEFORE.remove();
        return value;
    }

    /** Set the outcome snapshot recorded into after_json (aspect consumes it once; no second write channel). */
    public static void setAfter(Map<String, Object> after) {
        AFTER.set(after);
    }

    /** Take the outcome snapshot and clear it (single consumption, no thread-pool crosstalk). */
    public static Map<String, Object> takeAfter() {
        Map<String, Object> value = AFTER.get();
        AFTER.remove();
        return value;
    }

    
    public static void clear() {
        BEFORE.remove();
        AFTER.remove();
    }
}
