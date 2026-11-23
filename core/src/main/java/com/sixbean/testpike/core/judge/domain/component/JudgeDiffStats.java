package com.sixbean.testpike.core.judge.domain.component;

import java.util.List;


public final class JudgeDiffStats {

    private JudgeDiffStats() {
    }

    
    public static double welchT(List<Integer> a, List<Integer> b) {
        if (a == null || b == null || a.size() < 2 || b.size() < 2) {
            return Double.NaN;
        }
        double varA = sampleVariance(a);
        double varB = sampleVariance(b);
        double se2 = varA / a.size() + varB / b.size();
        if (se2 <= 0.0) {
            return Double.NaN;
        }
        return (mean(a) - mean(b)) / Math.sqrt(se2);
    }

    
    public static double twoSidedPNormal(double t) {
        if (Double.isNaN(t)) {
            return Double.NaN;
        }
        double z = Math.abs(t) / Math.sqrt(2.0);
        double pOneSided = 0.5 * erfc(z);
        double p = 2.0 * pOneSided;
        if (p < 0.0) {
            return 0.0;
        }
        return Math.min(p, 1.0);
    }

    
    private static double erfc(double x) {
        double t = 1.0 / (1.0 + 0.3275911 * Math.abs(x));
        double poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t;
        double erfcAbs = poly * Math.exp(-x * x);
        return x >= 0 ? erfcAbs : 2.0 - erfcAbs;
    }

    private static double mean(List<Integer> values) {
        long sum = 0;
        for (int v : values) {
            sum += v;
        }
        return (double) sum / values.size();
    }

    private static double sampleVariance(List<Integer> values) {
        double m = mean(values);
        double acc = 0.0;
        for (int v : values) {
            double d = v - m;
            acc += d * d;
        }
        return acc / (values.size() - 1);
    }
}
