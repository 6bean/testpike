package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Latency histogram bucket helper (raw material for full-run percentile recomputation;
 * the bucket boundary list is the single fixed contract for the whole pipeline).
 * <p>The executor packs counts per these boundaries when normalizing frames; the core judge
 * merges the arrays and reads full-run P50-P99 by nearest-rank. The old per-second p95-max
 * reading is mathematically unsound (merging per-second quantiles is not the whole-run
 * quantile). Storage form = JSON integer array aligned with {@link #boundaries()} (values
 * above the cap land in the last bucket); the O10 registry perf-p95 caliber SQL carries the
 * same array as an inline mirror, kept in step by the boundary-consistency test.
 */
public final class PerfHistogram {

    /** Upper bounds per bucket in milliseconds (ascending; the last bound is an open top —
     *  anything above it still reads as this value). */
    private static final double[] BOUNDARIES = {
            5, 10, 15, 20, 25, 30, 40, 50, 75, 100, 125, 150, 175, 200, 250, 300,
            400, 500, 750, 1000, 1500, 2000, 3000, 5000, 10000, 30000, 60000
    };

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PerfHistogram() {
    }

    /** Bucket upper bounds in ms (read-only copy; callers must not mutate — the array is the contract). */
    public static double[] boundaries() {
        return BOUNDARIES.clone();
    }

    /** Index of the bucket a single value falls into (0-based; over-cap values land in the last bucket). */
    public static int bucketOf(double valueMs) {
        for (int i = 0; i < BOUNDARIES.length; i++) {
            if (valueMs <= BOUNDARIES[i]) {
                return i;
            }
        }
        return BOUNDARIES.length - 1;
    }

    /** Bucket count array → JSON string (the persisted form). */
    public static String toJson(long[] counts) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < counts.length; i++) {
            sb.append(i > 0 ? "," : "").append(Math.max(counts[i], 0L));
        }
        return sb.append("]").toString();
    }

    /** JSON string → bucket count array (shorter arrays are zero-padded; a corrupt string
     *  yields empty buckets so the caller degrades to the fallback caliber instead of failing). */
    public static long[] fromJson(String raw) {
        long[] counts = new long[BOUNDARIES.length];
        if (raw == null || raw.isBlank()) {
            return counts;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!node.isArray()) {
                return counts;
            }
            for (int i = 0; i < Math.min(node.size(), counts.length); i++) {
                counts[i] = Math.max(node.get(i).asLong(0L), 0L);
            }
        } catch (Exception corrupt) {
            return new long[BOUNDARIES.length];
        }
        return counts;
    }

    /** Whether the buckets carry any sample (all-zero/empty = no raw material, judge falls back). */
    public static boolean hasSamples(long[] counts) {
        for (long c : counts) {
            if (c > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Merge per-frame histograms into one whole-run bucket array (frames without
     * buckets skipped; element-wise addition is safe because every persisted array
     * follows this shared boundary contract, open top bucket included).
     * <p>Single merge point for both calibers that recompute whole-run statistics —
     * the SLA judge and the scenario trend read face — so the two never diverge.
     */
    public static long[] merge(java.util.List<PerfMetricFrame> frames) {
        long[] merged = new long[BOUNDARIES.length];
        for (PerfMetricFrame f : frames) {
            if (f.hist() == null || f.hist().isBlank()) {
                continue;
            }
            long[] counts = fromJson(f.hist());
            for (int i = 0; i < merged.length; i++) {
                merged[i] += counts[i];
            }
        }
        return merged;
    }

    /**
     * Quantile by nearest-rank: with N = sum(counts) and rank = ceil(p x N), returns the first
     * bucket upper bound whose cumulative count reaches the rank (milliseconds).
     * <p>Conservative reading = at least p x N requests completed within the returned bound;
     * the report caliber footnote states the same.
     */
    public static double quantile(long[] counts, double percentile) {
        long total = 0;
        for (long c : counts) {
            total += c;
        }
        if (total <= 0) {
            return 0;
        }
        long rank = (long) Math.ceil(percentile * total);
        long running = 0;
        for (int i = 0; i < counts.length; i++) {
            running += counts[i];
            if (running >= rank) {
                return BOUNDARIES[i];
            }
        }
        return BOUNDARIES[BOUNDARIES.length - 1];
    }
}
