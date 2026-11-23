package com.sixbean.testpike.mockserver.domain.component;

import java.time.Duration;

/**
 * Test double that fails like an unreachable state store: every read and
 * write throws, driving the traffic plane's fail-closed spectra in L2
 * (degrade scenario stubs, refuse to pretend freshness).
 */
public class ScenarioStateStoreBroken implements MockScenarioStateStore {

    @Override
    public String read(String key) {
        throw new IllegalStateException("redis down");
    }

    @Override
    public void write(String key, String value, Duration ttl) {
        throw new IllegalStateException("redis down");
    }
}
