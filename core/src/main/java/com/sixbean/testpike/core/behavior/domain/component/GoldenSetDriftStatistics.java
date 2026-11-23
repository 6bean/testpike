package com.sixbean.testpike.core.behavior.domain.component;

import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftPair;
import com.sixbean.testpike.core.behavior.api.dto.GoldenDriftStat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure statistics of a golden-set replay comparison .
 * The same fixed inputs are pushed through the control and the candidate
 * template version of one model; a sample counts as drifted when the two
 * normalized output digests differ. The drift rate (percentage points) is the
 * quantity the promotion gate weighs against ai.behavior.drift-block-threshold.
 * Normalization is deliberately forgiving — case and inner whitespace runs
 * must not fake a drift — while answerability is strict: every pair needs a
 * key and two non-blank sides, and keys may repeat only never (a pack that
 * cannot be counted once cannot be trusted).
 */
public final class GoldenSetDriftStatistics {

    private GoldenSetDriftStatistics() {
    }

    /**
     * Computes the drift statistics over paired replay outputs. Digests are
     * compared case-insensitively with inner whitespace runs collapsed, so a
     * mere casing or spacing difference never counts as drift.
     *
     * @throws IllegalArgumentException on an empty/absent pair set, a blank sample
     *                                  key, a blank output side, or a duplicate key
     */
    public static GoldenDriftStat analyze(List<GoldenDriftPair> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            throw new IllegalArgumentException(
                    "golden drift needs at least one replayed sample pair — an empty set cannot prove stability");
        }
        Set<String> seenKeys = new HashSet<>();
        int drifted = 0;
        for (GoldenDriftPair pair : pairs) {
            if (pair == null) {
                throw new IllegalArgumentException("golden drift pair must not be null");
            }
            String key = pair.sampleKey() == null ? "" : pair.sampleKey().trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("golden drift pair needs a non-blank sample key");
            }
            if (!seenKeys.add(key)) {
                throw new IllegalArgumentException("duplicate sample key in one comparison: " + key);
            }
            String control = normalize(pair.controlDigest());
            String candidate = normalize(pair.candidateDigest());
            if (control.isEmpty() || candidate.isEmpty()) {
                throw new IllegalArgumentException(
                        "golden drift pair '" + key + "' carries a blank output digest on one side");
            }
            if (!control.equals(candidate)) {
                drifted++;
            }
        }
        BigDecimal rate = BigDecimal.valueOf(drifted)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(pairs.size()), 2, RoundingMode.HALF_UP);
        return new GoldenDriftStat(pairs.size(), drifted, rate);
    }

    /** Case-insensitive, whitespace-collapsed form of an output digest. */
    static String normalize(String digest) {
        if (digest == null) {
            return "";
        }
        return digest.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
