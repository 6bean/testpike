package com.sixbean.testpike.aiservice.llm.domain.component;


public final class SysConfigInts {

    private SysConfigInts() {
    }

    
    public static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    
    public static int positiveInt(String raw, int fallback) {
        int value = parseInt(raw, fallback);
        return value > 0 ? value : fallback;
    }

    /**
     * Interval-tolerant tier (same shape as SysConfigDoubles.parse): blank /
     * non-numeric / outside [min, max] falls back. Shared by the capability
     * reprobe scheduler and AiCtxConfigResolver — a second consumer means one
     * source here, no per-caller interval copies.
     */
    public static int parseInt(String raw, int min, int max, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // degrade to the seeded default — one hand-typed value never steers the job
        }
        return fallback;
    }
}
