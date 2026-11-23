package com.sixbean.testpike.mockserver.domain.component;

import java.time.Duration;

/**
 * Seam between the scenario stepping logic and the lane-state store (
 * , shaped like the hit-batch sink seam: L2 tests plug in-memory or
 * broken doubles while the shipped wiring talks to Redis — the real store
 * path is proven at L3/L4, never mocked there.
 * <p>Keys are the logical lane identity {@code space|scenario|scope|lane};
 * the transport-side key layout is the implementation's concern. Exceptions
 * mean "state unknown" and must propagate: the controller fails closed on
 * scenario traffic instead of pretending a state nobody confirmed.
 */
public interface MockScenarioStateStore {

    /** Current state label of one lane, or null when the lane is fresh. */
    String read(String key);

    /** Store one advance with an idle expiry (mock.scenario.ttl-minutes). */
    void write(String key, String value, Duration ttl);
}
