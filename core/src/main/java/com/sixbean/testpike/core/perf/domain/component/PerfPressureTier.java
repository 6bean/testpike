package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Peak-concurrency tier of a pressure plan (single source, shared by the launch
 * gate and the CF-5 capacity pre-flight card).
 * <p>Moved verbatim out of {@code PerfRunServiceImpl.peakConcurrency} so a
 * second derivation can never drift from the one the A-1 gate judges by
 * (red line 6: same number or nothing). Semantics unchanged:
 * CONSTANT_RPS/SOAK use targetRps, RAMP/SPIKE/BUSINESS_CURVE use peakRps,
 * FIXED_VU's user pool IS the tier, LADDER plans for the search top
 * (startRps + stepRps x steps); anything unparseable or missing yields
 * null = fail-closed for the gate, no-claim for the card.
 */
@Component
public class PerfPressureTier {

    private final ObjectMapper objectMapper;

    public PerfPressureTier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Tier from the model code + pressure config JSON text; null = no claim. */
    public Integer of(String model, String pressureConfigJson) {
        try {
            Map<String, Object> config = objectMapper.readValue(pressureConfigJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            // LADDER has no peakRps key — the search top is startRps + stepRps x steps,
            // computed before the generic key lookup so a staircase scenario is never
            // rejected at launch for a "missing tier" . A malformed
            // staircase (any key absent/non-numeric) still yields null = gate rejects
            // fail-closed, preserving the original guard posture.
            if ("LADDER".equals(model)) {
                Integer start = positiveIntAt(config, "startRps");
                Integer step = nonNegativeIntAt(config, "stepRps");
                Integer count = positiveIntAt(config, "steps");
                return start != null && step != null && count != null ? start + step * count : null;
            }
            String key = "CONSTANT_RPS".equals(model) || "SOAK".equals(model)
                    ? "targetRps" : "peakRps";
            Object value = config.get(key);
            if (value instanceof Number n) {
                return n.intValue();
            }
            if ("FIXED_VU".equals(model) && config.get("vu") instanceof Number pool) {
                return pool.intValue();
            }
            return null;
        } catch (Exception corrupt) {
            return null;
        }
    }

    /** Tier-deriving read: missing / non-numeric = null (fail-closed for the launch gate). */
    private static Integer positiveIntAt(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v instanceof Number n && n.intValue() > 0 ? n.intValue() : null;
    }

    private static Integer nonNegativeIntAt(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v instanceof Number n && n.intValue() >= 0 ? n.intValue() : null;
    }
}
