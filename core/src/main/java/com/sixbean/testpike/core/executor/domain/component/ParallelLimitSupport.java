package com.sixbean.testpike.core.executor.domain.component;

import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.springframework.stereotype.Component;


@Component
public class ParallelLimitSupport {

    
    public static final String GLOBAL_MAX_KEY = "exec.parallel.max";
    
    public static final String PER_PROJECT_MAX_KEY = "exec.parallel.per-project";
    
    public static final int DEFAULT_UNLIMITED = 0;

    private final ConfigCacheHolder configCacheHolder;

    public ParallelLimitSupport(ConfigCacheHolder configCacheHolder) {
        this.configCacheHolder = configCacheHolder;
    }

    
    public int globalMax() {
        return configInt(GLOBAL_MAX_KEY, DEFAULT_UNLIMITED);
    }

    
    public int perProjectMax() {
        return configInt(PER_PROJECT_MAX_KEY, DEFAULT_UNLIMITED);
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
