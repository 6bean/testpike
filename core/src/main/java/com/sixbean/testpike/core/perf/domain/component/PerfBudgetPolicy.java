package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Perf run budget policy (A-14 self-healing): converts a scenario's declared run
 * duration plus the hot-updatable margin into the dispatch-envelope budget in
 * milliseconds. Single point for three consumers — the dispatcher writes
 * {@code budgetMs} into the envelope, the claim path hands it to the executor as
 * timeoutMs for perf tasks only, and the server-side timeout sweep judges a perf
 * row by the same value instead of the global case timeout.
 * <p>Duration keys mirror the save-gate required set in
 * {@code PerfScenarioServiceImpl.REQUIRED_KEYS} (CONSTANT_RPS/SPIKE =
 * durationSeconds; RAMP = rampSeconds + holdSeconds). An unknown model or a
 * missing/non-positive value yields 0, meaning "no budget claim" — every
 * consumer then falls back to the global timeout key (zero-drift for legacy
 * envelopes and non-standard scenarios).
 */
public final class PerfBudgetPolicy {

    /** Dispatch-envelope budget key (written by the dispatcher, read at claim/sweep). */
    public static final String ENVELOPE_BUDGET_MS_KEY = "budgetMs";

    private PerfBudgetPolicy() {
    }

    /**
     * Declared run duration in seconds for the pressure model, read from the
     * pinned pressure config. Unknown model / missing / non-positive = 0.
     */
    public static double durationSecondsOf(String pressureModel, JsonNode pressureConfig) {
        if (pressureModel == null || pressureConfig == null) {
            return 0;
        }
        return switch (pressureModel) {
            case "CONSTANT_RPS", "SPIKE" -> positiveOrZero(pressureConfig.path("durationSeconds"));
            case "RAMP" -> positiveOrZero(pressureConfig.path("rampSeconds"))
                    + positiveOrZero(pressureConfig.path("holdSeconds"));
            // B-2 : FIXED_VU claims only its declared window — a loops-only pool
            // has no derivable wall-clock horizon and yields 0 = no claim (the executor's
            // own loop bound ends it; the global timeout stays the outer guard).
            case "FIXED_VU" -> positiveOrZero(pressureConfig.path("durationSeconds"));
            case "LADDER" -> positiveOrZero(pressureConfig.path("stepSeconds"))
                    * positiveOrZero(pressureConfig.path("steps"));
            case "BUSINESS_CURVE" -> positiveOrZero(pressureConfig.path("durationSeconds"));
            case "SOAK" -> positiveOrZero(pressureConfig.path("durationHours")) * 3600D;
            default -> 0;
        };
    }

    /**
     * Budget in milliseconds = (duration + margin minutes) scaled, or 0 when the
     * scenario declares no usable duration (consumer falls back to the global key).
     */
    public static long budgetMs(double durationSeconds, int marginMinutes) {
        if (durationSeconds <= 0) {
            return 0L;
        }
        return (long) ((durationSeconds + marginMinutes * 60.0) * 1000.0);
    }

    /**
     * Declared run duration in seconds read from a pinned launch snapshot
     * ({@code pressureModel} + {@code pressureConfig} stored as its JSON text).
     * Shared by the launch duration ceiling (A-9), the environment-lock release
     * estimate (GA-2) and the terminal data-loss check — single duration source,
     * no second dialect. Any parse failure / unknown model = 0 = no claim.
     */
    public static double durationSecondsOfSnapshot(ObjectMapper objectMapper, String snapshotJson) {
        try {
            com.fasterxml.jackson.databind.JsonNode snapshot =
                    objectMapper.readTree(snapshotJson);
            String model = snapshot.path("pressureModel").asText("");
            com.fasterxml.jackson.databind.JsonNode config =
                    objectMapper.readTree(snapshot.path("pressureConfig").asText("{}"));
            return durationSecondsOf(model.isBlank() ? null : model, config);
        } catch (Exception unparsable) {
            return 0D;
        }
    }

    private static double positiveOrZero(JsonNode value) {
        double d = value.asDouble(0);
        return d > 0 ? d : 0;
    }
}
