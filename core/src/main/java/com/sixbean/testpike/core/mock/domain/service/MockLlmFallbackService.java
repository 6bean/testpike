package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackAnswer;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackRequest;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackStoreRequest;
import com.sixbean.testpike.core.mock.api.dto.MockLlmFallbackStoreResult;

/**
 * A-3 LLM fallback answer plane (core control-plane orchestration, .
 * <p>Consumed by the mock-server miss branch through the internal door: cache
 * reads land here first (zero token spend, instant answer), and only a cold
 * miss would escalate to the generation leg provider chain via
 * ai-service, cost pinned through the budget keys). This plane never calls a
 * model in-process; keys stay in ai-service.
 * <p>Gate spectrum = the same dual-gate matrix as the drafting jobs: master
 * {@code ai.mock.enabled} ANDed with {@code ai.mock.llm-fallback}; a closed
 * gate refuses in plain words naming the exact key. An open-gate miss answers
 * honestly, never with a pretend body.
 */
public interface MockLlmFallbackService {

    /**
     * Look up the generated-answer cache for one masked request fingerprint.
     * Fresh rows serve instantly and bump the hit ledger; expired rows are
     * never served; a cold space answers an honest miss with plain words.
     */
    MockLlmFallbackAnswer answer(MockLlmFallbackRequest request);

    /**
 * Case a freshly paid generation into the cache write side).
 * <p>Same dual gates as reads (a closed gate refuses naming the exact
 * key, nothing persisted). Over-budget generations are refused in plain
 * words rather than stored — the token ceiling guards both directions so
 * an oversized artifact never becomes a pre-paid future answer. One
 * fingerprint keeps one row: a newer answer replaces the stale artifact
 * and resets its hit ledger (the old artifact's statistics describe a
 * body that no longer exists).
 */
    MockLlmFallbackStoreResult store(MockLlmFallbackStoreRequest request);

    /**
 * Fingerprint key scheme, single source: uppercase method, normalized
 * path, masked request digest. Writers and readers (this plane)
 * must pair on this exact shape — PII never enters the key.
 */
    static String cacheKeyFor(String method, String path, String maskedFingerprint) {
        String m = method == null ? "GET" : method.trim().toUpperCase();
        String p = path == null ? "/" : path.trim();
        String fp = maskedFingerprint == null ? "" : maskedFingerprint.trim();
        String key = m + "|" + p + "|" + fp;
        return key.length() > 200 ? key.substring(0, 200) : key;
    }
}
