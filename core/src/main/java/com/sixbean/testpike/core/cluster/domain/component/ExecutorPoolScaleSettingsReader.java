package com.sixbean.testpike.core.cluster.domain.component;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolScaleSettings;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.springframework.stereotype.Component;

/**
 * Assembles the autoscaler guardrail snapshot from the cluster.autoscale-* and
 * cluster.budget-* configuration keys at call time.
 *
 * <p>Why it exists: every bound the pool autoscaler respects (replica floor and ceiling,
 * wait threshold that justifies extra replicas, cooldown, the elastic pause switch and
 * the budget protection pair) is an operator-tunable value stored in tp_sys_config, so a
 * fleet can be retuned without a rebuild. Each field falls back to a shipped default when
 * the key is absent or malformed; the defaults intentionally equal the factory seed rows
 * (db/migration V631 + the schema-init replay) so a half-configured install scales exactly
 * like a fresh one. When min exceeds max (an operator typo), max is lifted to min while the
 * contradiction lasts: scale-out stays bounded and scale-in below the floor is prevented.
 */
@Component
public class ExecutorPoolScaleSettingsReader {

    static final String K_CAPACITY = "cluster.autoscale-capacity-per-executor";
    static final String K_MIN = "cluster.autoscale-min-replicas";
    static final String K_MAX = "cluster.autoscale-max-replicas";
    static final String K_P95 = "cluster.autoscale-p95-wait-threshold-seconds";
    static final String K_COOLDOWN = "cluster.autoscale-cooldown-seconds";
    static final String K_PAUSED = "cluster.autoscale-paused";
    static final String K_BUDGET_UTIL = "cluster.budget-max-utilization";
    static final String K_BUDGET_PAUSE = "cluster.budget-pause-enabled";

    // Shipped defaults; must stay identical to the factory seed rows of V631.
    static final int DEF_CAPACITY = 2;
    static final int DEF_MIN = 1;
    static final int DEF_MAX = 8;
    static final int DEF_P95 = 300;
    static final int DEF_COOLDOWN = 120;
    static final double DEF_BUDGET_UTIL = 0.85d;

    private final ConfigCacheHolder configCacheHolder;

    public ExecutorPoolScaleSettingsReader(ConfigCacheHolder configCacheHolder) {
        this.configCacheHolder = configCacheHolder;
    }

    /** Fresh guardrail snapshot; reads every key through the shared config cache. */
    public ExecutorPoolScaleSettings read() {
        int capacity = intOr(K_CAPACITY, DEF_CAPACITY, 1, 1000);
        int min = intOr(K_MIN, DEF_MIN, 0, 10000);
        int max = intOr(K_MAX, DEF_MAX, 0, 10000);
        if (max < min) {
            max = min;
        }
        int p95 = intOr(K_P95, DEF_P95, 0, Integer.MAX_VALUE);
        int cooldown = intOr(K_COOLDOWN, DEF_COOLDOWN, 0, Integer.MAX_VALUE);
        boolean paused = flagOr(K_PAUSED, false);
        double budgetUtil = doubleOr(K_BUDGET_UTIL, DEF_BUDGET_UTIL, 0.01d, 1.0d);
        boolean budgetPause = flagOr(K_BUDGET_PAUSE, true);
        return new ExecutorPoolScaleSettings(
                capacity, min, max, p95, cooldown, paused,
                budgetUtil, budgetPause, System.currentTimeMillis() / 1000L);
    }

    private String raw(String key) {
        try {
            return configCacheHolder.get(key);
        } catch (RuntimeException ex) {
            // A cache miss shape must degrade to the shipped default, never break scaling.
            return null;
        }
    }

    private int intOr(String key, int fallback, int low, int high) {
        String value = raw(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return (parsed < low || parsed > high) ? fallback : parsed;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private double doubleOr(String key, double fallback, double low, double high) {
        String value = raw(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(value.trim());
            return (parsed < low || parsed > high) ? fallback : parsed;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean flagOr(String key, boolean fallback) {
        String value = raw(key);
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if ("1".equals(trimmed)) {
            return true;
        }
        if ("0".equals(trimmed)) {
            return false;
        }
        return fallback;
    }
}
