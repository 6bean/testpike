package com.sixbean.testpike.mockserver.domain.component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Test double of the scenario state store: an in-memory map standing in for
 * Redis inside L2 unit tests only (infra isolation — the real Redis path is
 * proven at L3/L4, never mocked there). Remembers the last written key/ttl so
 * stepping tests can assert the lane identity and the configured expiry.
 */
public class ScenarioStateStoreInMemory implements MockScenarioStateStore {

    public final Map<String, String> values = new HashMap<>();
    public String lastKey;
    public Duration lastTtl;

    @Override
    public String read(String key) {
        return values.get(key);
    }

    @Override
    public void write(String key, String value, Duration ttl) {
        values.put(key, value);
        this.lastKey = key;
        this.lastTtl = ttl;
    }
}
