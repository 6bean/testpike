package com.sixbean.testpike.aiservice.llm.domain.component;


public final class CalibratedDelegator implements TokenEstimator {

    private final TokenEstimator base;
    private final double coefficient;

    public CalibratedDelegator(TokenEstimator base, double coefficient) {
        if (base == null) {
            throw new IllegalArgumentException("base engine is required (calibrated layer must not delegate to null)");
        }
        if (!(coefficient > 0) || Double.isInfinite(coefficient)) {
            throw new IllegalArgumentException("calibration coefficient must be a positive finite number: " + coefficient);
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
