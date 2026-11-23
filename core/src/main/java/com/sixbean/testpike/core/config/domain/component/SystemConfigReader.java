package com.sixbean.testpike.core.config.domain.component;

import org.springframework.stereotype.Component;


@Component
public class SystemConfigReader {

    private final ConfigCacheHolder configCacheHolder;

    public SystemConfigReader(ConfigCacheHolder configCacheHolder) {
        this.configCacheHolder = configCacheHolder;
    }

    
    public int getInt(String key, int defaultValue) {
        try {
            String cached = configCacheHolder.get(key);
            return cached == null ? defaultValue : Integer.parseInt(cached.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    
    public long getLong(String key, long defaultValue) {
        try {
            String cached = configCacheHolder.get(key);
            return cached == null ? defaultValue : Long.parseLong(cached.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * Read a positive-integer system setting (ceilings, timeouts, widths — keys that only make
     * sense above zero): missing, unparsable, zero or negative all fall back to the default, so a
     * caller never has to re-write the parse-and-guard pair.
     * <p>Extracted because the pass-through door had grown two copies of this logic (byte/timeout
     * ceiling in one component, quota window in another): the second consumer makes it a shared
     * piece, so one future change cannot leave the other behind.
     */
    public long getPositiveLong(String key, long defaultValue) {
        long parsed;
        try {
            String cached = configCacheHolder.get(key);
            parsed = cached == null ? defaultValue : Long.parseLong(cached.trim());
        } catch (Exception e) {
            return defaultValue;
        }
        return parsed > 0 ? parsed : defaultValue;
    }

    
    public String getString(String key, String defaultValue) {
        String cached = configCacheHolder.get(key);
        return cached == null || cached.isBlank() ? defaultValue : cached.trim();
    }

    /**
     * Raw cached value with no default fallback — the input a tri-state
     * governance door needs ("row absent" must stay distinguishable from
     * "row says off"). Null = absent; callers resolve semantics through the
     * shared FactoryOnSwitch (R40 single source).
     */
    public String getRaw(String key) {
        return configCacheHolder.get(key);
    }

    
    public boolean getBoolean(String key, boolean defaultValue) {
        String cached = configCacheHolder.get(key);
        if (cached == null || cached.isBlank()) {
            return defaultValue;
        }
        String v = cached.trim();
        if ("1".equals(v) || "true".equalsIgnoreCase(v)) {
            return true;
        }
        if ("0".equals(v) || "false".equalsIgnoreCase(v)) {
            return false;
        }
        return defaultValue;
    }
}
