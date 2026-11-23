package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Single-source scenario reading for the perf engine adapters.
 *
 * <p>The field lookup, the JSON parse with its neutral failure wording, and
 * the login-strategy compatibility predicate used to live verbatim inside
 * every adapter. Duplicating them across adapters is a drift hazard (a
 * fail-closed rule changed in one engine silently not in the others), so
 * each adapter now delegates here and keeps only its engine-specific refusal
 * message. Consumers must reference these methods; re-copying the bodies is
 * forbidden by the single-source rule.</p>
 */
public final class PerfScenarioReadSupport {

    private PerfScenarioReadSupport() {
    }

    /** Null-safe string column read shared by all scenario snapshots. */
    public static String text(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * Parse a scenario JSON column, failing fast with the same user-facing
     * recovery wording no matter which engine asked for it.
     */
    public static JsonNode readJson(ObjectMapper mapper, String raw, String what) {
        try {
            return mapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (IOException corrupt) {
            throw new ServiceException("场景" + what + "解析失败，请重新保存场景后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * Collection-stage session guard: true when the handle carried back by
     * the runner belongs to the asking engine. An adapter must never read
     * frames produced under another engine's session, so every adapter's
     * collect stage routes through this predicate instead of re-copying the
     * null-and-prefix idiom.
     */
    public static boolean sessionMatches(String engineSession, String schemePrefix) {
        return engineSession != null && engineSession.startsWith(schemePrefix);
    }

    /**
     * Collection-stage placeholder for engines whose frames flow back through
     * the perf_worker ingestion chain instead of this call: an alien session
     * handle and a not-yet-ingested session both yield zero frames, and the
     * adapter must never fabricate engine self-reported statistics into the
     * frame stream. New adapters consume this instead of re-copying the
     * guard-and-empty idiom.
     */
    public static List<PerfMetricFrame> collectUntilIngested(String engineSession,
            String schemePrefix) {
        if (!sessionMatches(engineSession, schemePrefix)) {
            return List.of();
        }
        return List.of();
    }

    /** True when the declared login strategy needs one session reused across users. */
    public static boolean declaresSharedSessionStrategy(String strategy) {
        return "SHARED".equals(strategy)
                || (strategy != null && strategy.startsWith("PER_SESSION_"));
    }

    /**
     * Fail-closed compile gate for engines whose threads keep per-thread
     * session state: a SHARED / PER_SESSION_N strategy cannot be rendered
     * truthfully there, and silently degrading to per-user logins would fake
     * the declared traffic shape. The predicate is single-sourced above; each
     * engine passes its own refusal wording so the user learns which engine
     * can carry the strategy.
     */
    public static void rejectSharedSessionStrategy(Map<String, Object> scenario,
            String engineRefusal) {
        String strategy = text(scenario, "login_strategy");
        if (declaresSharedSessionStrategy(strategy)) {
            throw new ServiceException("登录策略 " + strategy + engineRefusal,
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
