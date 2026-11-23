package com.sixbean.testpike.aiservice.llm.domain.component;


public final class CalibratedDelegator implements TokenEstimator {

    private final TokenEstimator base;
    private final double coefficient;

    public CalibratedDelegator(TokenEstimator base, double coefficient) {
        if (base == null) {
            throw new IllegalArgumentException("基座引擎必填（calibrated 层禁止委托 null）");
        }
        if (!(coefficient > 0) || Double.isInfinite(coefficient)) {
            throw new IllegalArgumentException("校准系数必须为正有限数: " + coefficient);
        }
        this.base = base;
        this.coefficient = coefficient;
    }

    @Override
    public String engineId() {
        return "calibrated-" + base.engineId();
    }

    @Override
    public int countTokens(String text) {
        int raw = base.countTokens(text);
        if (raw <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(raw * coefficient));
    }

    
    public double coefficient() {
        return coefficient;
    }

    
    public TokenEstimator base() {
        return base;
    }
}
