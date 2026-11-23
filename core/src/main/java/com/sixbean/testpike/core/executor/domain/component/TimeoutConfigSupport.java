package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.springframework.stereotype.Component;


@Component
public class TimeoutConfigSupport {

    
    public static final String CASE_TIMEOUT_MINUTES_KEY = "exec.case-timeout-minutes";
    
    public static final String BATCH_TIMEOUT_HOURS_KEY = "exec.batch-timeout-hours";
    
    public static final int DEFAULT_CASE_TIMEOUT_MINUTES = 10;
    public static final int DEFAULT_BATCH_TIMEOUT_HOURS = 4;

    private final ConfigCacheHolder configCacheHolder;

    public TimeoutConfigSupport(ConfigCacheHolder configCacheHolder) {
        this.configCacheHolder = configCacheHolder;
    }

 
    public int caseTimeoutMinutes() {
        return configInt(CASE_TIMEOUT_MINUTES_KEY, DEFAULT_CASE_TIMEOUT_MINUTES);
    }

    
    public int batchTimeoutHours() {
        return configInt(BATCH_TIMEOUT_HOURS_KEY, DEFAULT_BATCH_TIMEOUT_HOURS);
    }

    
    private int configInt(String key, int defaultValue) {
        try {
            String cached = configCacheHolder.get(key);
            return cached == null ? defaultValue : Integer.parseInt(cached.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
