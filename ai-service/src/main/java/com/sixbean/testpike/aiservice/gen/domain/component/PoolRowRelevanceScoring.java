package com.sixbean.testpike.aiservice.gen.domain.component;

import java.util.Map;

/**
 * Relevance scoring of one project-pool row against a lowered text face —
 * the single scoring source ( slices a + c; AGENTS rule 6: a formula
 * lives in exactly one place). Extracted verbatim from
 * {@code AiGenServiceImpl.rowRelevanceScore} so the stage-2 prompt ranking,
 * the cross-category merge and the mechanical gap reconciliation all consume
 * the SAME formula — a second copy would let "is this reference covered by
 * the pool" drift apart from "which rows ride the prompt".
 * <p>Two faces, one implementation:
 * <ul>
 * <li>{@link #textMatchScore} — pure coverage: a row counts as covered
 * when the text contains one of its name/tag/description tokens or
 * the row's whole name verbatim. This is the face the reconciliation
 * asks ("does any pool row back this reference?").</li>
 * <li>{@link #rowRelevanceScore} — textMatchScore plus the +1 usage hint
 * (positive requestCount). This is the ordering face used by ranking
 * and merging, where a busy row deserves a better slot even with no
 * text overlap.</li>
 * </ul>
 * Pure in-memory, zero IO, never throws on null rows or keys.
 */
public final class PoolRowRelevanceScoring {

    private PoolRowRelevanceScoring() {
    }

    /** Blank-safe text face of one pool row. */
    public static String rowText(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    /**
     * Coverage face: distinct name/tag/description tokens found in the text
     * (token length ≥ 2) plus a +2 whole-name mention. A score above zero
     * means the text demonstrably references this row.
     */
    public static int textMatchScore(String lowerCaseText, Map<String, Object> row) {
        String name = rowText(row, "name");
        String matchFace = (name + " " + rowText(row, "tags") + " "
                + rowText(row, "description")).toLowerCase(java.util.Locale.ROOT);
        int s = 0;
        if (lowerCaseText != null && !lowerCaseText.isEmpty()) {
            for (String token : matchFace.split("[^\\p{L}\\p{N}]+")) {
                if (token.length() >= 2 && lowerCaseText.contains(token)) {
                    s++;
                }
            }
            if (!name.isEmpty() && lowerCaseText.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                s += 2;
            }
        }
        return s;
    }

    /**
     * Ordering face: the coverage score plus the +1 usage hint for rows with
     * a positive requestCount (semantics byte-identical to the former
     * private AiGenServiceImpl scorer).
     */
    public static int rowRelevanceScore(String lowerCaseText, Map<String, Object> row) {
        int s = textMatchScore(lowerCaseText, row);
        Object usage = row == null ? null : row.get("requestCount");
        if (usage instanceof Number num && num.longValue() > 0) {
            s += 1;
        }
        return s;
    }
}
