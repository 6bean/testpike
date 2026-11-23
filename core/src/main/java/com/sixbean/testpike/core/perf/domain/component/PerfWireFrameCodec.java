package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wire-frame codec : turns executor frame maps (the
 * 03 widened frame shape) into {@link PerfMetricFrame} contract records. Two
 * consumers share this single mapping — the result-channel finalizer (perfFrames)
 * and the heartbeat partial-frame ingestor (perfPartial) — so the bucket columns
 * can never drift between the two channels.
 * <p>Zero judgment lives here: value ranges / volume / replay anchors all sit at
 * the single ingestion gate (PerfMetricService), never a second rule set. Missing
 * keys keep NULL semantics (legacy executor shape — never 0-substituted).
 */
@Component
public class PerfWireFrameCodec {

    private final ObjectMapper objectMapper;

    public PerfWireFrameCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Wire rows -> frame contract (absent/non-numeric keys fall back per field;
     *  non-list payloads map to an empty batch, skipped honestly upstream). */
    public List<PerfMetricFrame> toFrames(Object raw) {
        List<PerfMetricFrame> frames = new ArrayList<>();
        for (Map<String, Object> row : mapRows(raw)) {
            frames.add(new PerfMetricFrame(
                    intValue(row.get("secondOffset"), 0),
                    doubleValue(row.get("rps"), 0.0),
                    doubleValue(row.get("p95Ms"), 0.0),
                    doubleValue(row.get("errorRate"), 0.0),
                    nullableDouble(row.get("ttftMs")),
                    nullableDouble(row.get("itlMs")),
                    nullableDouble(row.get("tokensPerSec")),
                    nullableDouble(row.get("authMs")),
                    nullableDouble(row.get("authCount")),
                    nullableLong(row.get("reqCount")),
                    nullableLong(row.get("failCount")),
                    nullableDouble(row.get("durationSumMs")),
                    jsonCell(row.get("hist")),
                    jsonCell(row.get("errorClass")),
                    row.get("stepKey") == null ? "" : String.valueOf(row.get("stepKey")),
                    jsonCell(row.get("hostStats"))));
        }
        return frames;
    }

    /** Row-level host sample (03 §2.2 perfPartial: the executor samples the press
     *  box once per due window and rides the reading beside the frames, not inside
     *  each frame) -> first-class hostStats on every frame of that window. Frames
     *  that already declare their own sample keep it; absent/dirty sample payload
     *  = no adoption (NULL stays NULL — never a fabricated reading). */
    public List<PerfMetricFrame> attachRowHostSample(List<PerfMetricFrame> frames,
                                                     Object rowHostStats) {
        String sample = jsonCell(rowHostStats);
        if (sample == null) {
            return frames;
        }
        return frames.stream().map(f -> f.withHostStatsIfAbsent(sample)).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapRows(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add((Map<String, Object>) map);
            }
        }
        return rows;
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static double doubleValue(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    /** Bucket-count fields: absent/non-numeric = NULL (legacy executor shape; the
     *  judge falls back to the per-second caliber — never a 0 substitution). */
    private static Long nullableLong(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    /** JSON payload cells (hist array / errorClass object) -> compact JSON strings for
     *  the jsonb columns; absent/blank = NULL; serialization failure = NULL (the value-
     *  range gate downstream degrades to the fallback caliber, never a corrupt row). */
    private String jsonCell(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s.isBlank() ? null : s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception corrupt) {
            return null;
        }
    }

    /** AI-family first-class fields: absent/non-numeric = NULL (0-substitution is
     *  forbidden — the ingestion gate's discipline). */
    private static Double nullableDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
