package com.sixbean.testpike.aiservice.doc.domain.component;

/**
 * <ul>
 * </ul>
 *
 */
public record BudgetPolicy(double summaryRatio, int summaryMinTokens, int summaryMaxTokens,
                           int reduceTriggerGroups, int reduceMaxLevels) {

    public static final String KEY_SUMMARY_RATIO = "ai.doc.summary-ratio";
    public static final String KEY_SUMMARY_MIN_TOKENS = "ai.doc.summary-min-tokens";
    public static final String KEY_SUMMARY_MAX_TOKENS = "ai.doc.summary-max-tokens";
    public static final String KEY_REDUCE_TRIGGER_GROUPS = "ai.doc.reduce-trigger-groups";
    public static final String KEY_REDUCE_MAX_LEVELS = "ai.doc.reduce-max-levels";

    
    public static final double DEFAULT_RATIO = 0.20;
    public static final int DEFAULT_SUMMARY_MIN_TOKENS = 300;
    public static final int DEFAULT_SUMMARY_MAX_TOKENS = 1500;
    public static final int DEFAULT_REDUCE_TRIGGER_GROUPS = 6;
    public static final int DEFAULT_REDUCE_MAX_LEVELS = 2;

    
    public static final double RATIO_MIN = 0.05;
    public static final double RATIO_MAX = 0.9;
    public static final int SUMMARY_TOKENS_MIN = 50;
    public static final int SUMMARY_TOKENS_MAX = 20000;
    public static final int TRIGGER_MIN = 2;
    public static final int TRIGGER_MAX = 64;
    public static final int LEVELS_MIN = 1;
    public static final int LEVELS_MAX = 3;

    public BudgetPolicy {
        if (summaryRatio < RATIO_MIN || summaryRatio > RATIO_MAX) {
            throw new IllegalArgumentException("summary-ratio 越界: " + summaryRatio);
        }
        if (summaryMinTokens < SUMMARY_TOKENS_MIN || summaryMaxTokens > SUMMARY_TOKENS_MAX
                || summaryMinTokens > summaryMaxTokens) {
            throw new IllegalArgumentException("摘要 token 上下限非法: ["
                    + summaryMinTokens + "," + summaryMaxTokens + "]");
        }
        if (reduceTriggerGroups < TRIGGER_MIN || reduceTriggerGroups > TRIGGER_MAX) {
            throw new IllegalArgumentException("reduce-trigger-groups 越界: " + reduceTriggerGroups);
        }
        if (reduceMaxLevels < LEVELS_MIN || reduceMaxLevels > LEVELS_MAX) {
            throw new IllegalArgumentException("reduce-max-levels 越界: " + reduceMaxLevels);
        }
    }

    public static BudgetPolicy defaults() {
        return new BudgetPolicy(DEFAULT_RATIO, DEFAULT_SUMMARY_MIN_TOKENS,
                DEFAULT_SUMMARY_MAX_TOKENS, DEFAULT_REDUCE_TRIGGER_GROUPS, DEFAULT_REDUCE_MAX_LEVELS);
    }

    
    public static BudgetPolicy fromConfig(String ratio, String minTokens, String maxTokens,
                                          String triggerGroups, String maxLevels) {
        double r = parseDouble(ratio, DEFAULT_RATIO);
        int min = parseInt(minTokens, DEFAULT_SUMMARY_MIN_TOKENS);
        int max = parseInt(maxTokens, DEFAULT_SUMMARY_MAX_TOKENS);
        int trigger = parseInt(triggerGroups, DEFAULT_REDUCE_TRIGGER_GROUPS);
        int levels = parseInt(maxLevels, DEFAULT_REDUCE_MAX_LEVELS);
        if (r < RATIO_MIN || r > RATIO_MAX) {
            r = DEFAULT_RATIO;
        }
        if (min < SUMMARY_TOKENS_MIN || min > SUMMARY_TOKENS_MAX) {
            min = DEFAULT_SUMMARY_MIN_TOKENS;
        }
        if (max < SUMMARY_TOKENS_MIN || max > SUMMARY_TOKENS_MAX) {
            max = DEFAULT_SUMMARY_MAX_TOKENS;
        }
        if (min > max) {
            min = DEFAULT_SUMMARY_MIN_TOKENS;
            max = DEFAULT_SUMMARY_MAX_TOKENS;
        }
        if (trigger < TRIGGER_MIN || trigger > TRIGGER_MAX) {
            trigger = DEFAULT_REDUCE_TRIGGER_GROUPS;
        }
        if (levels < LEVELS_MIN || levels > LEVELS_MAX) {
            levels = DEFAULT_REDUCE_MAX_LEVELS;
        }
        return new BudgetPolicy(r, min, max, trigger, levels);
    }

    
    public int summaryBudgetTokens(int groupBudgetTokens) {
        int scaled = (int) Math.round(groupBudgetTokens * summaryRatio);
        return Math.min(Math.max(scaled, summaryMinTokens), summaryMaxTokens);
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
