package com.sixbean.testpike.mockserver.domain.component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.DoubleSupplier;

/**
 * Scripted roll source for fault-decision tests: each draw pops one prepared
 * value, the last value repeats so a test only scripts the rolls it asserts
 * on. Keeps every injection branch deterministic (no real randomness).
 */
final class RollingSupplier implements DoubleSupplier {

    private final Deque<Double> values = new ArrayDeque<>();

    RollingSupplier of(double... prepared) {
        for (double v : prepared) {
            values.add(v);
        }
        return this;
    }

    @Override
    public double getAsDouble() {
        if (values.size() > 1) {
            return values.pollFirst();
        }
        return values.isEmpty() ? 0.0 : values.peek();
    }
}
