package com.sixbean.testpike.core.judge.domain.component;


public final class JudgeConfigValues {

    private JudgeConfigValues() {
    }

    
    public static Double parseDouble(String raw, double lo, double hi) {
        if (raw == null) {
            return null;
        }
        try {
            double v = Double.parseDouble(raw.trim());
            if (Double.isNaN(v) || v < lo || v > hi) {
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    
    public static Integer parseInt(String raw, int lo, int hi) {
        if (raw == null) {
            return null;
        }
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < lo || v > hi) {
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
