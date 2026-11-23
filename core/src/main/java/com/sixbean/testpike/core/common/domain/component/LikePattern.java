package com.sixbean.testpike.core.common.domain.component;

/**
 * SQL LIKE/ILIKE pattern common component extracted to a lower layer from the original private CaseServiceImpl implementation after being reused at least twice.
 * <p>Escape wildcard characters so that %, _, and backslash match literally when used with ESCAPE '\'.
 * Normalize keywords by trimming and truncating overlength values to at most 50 characters.
 *
 */
public final class LikePattern {

    /** Define the maximum effective keyword length and truncate overlength values to prevent degradation into a full table scan. */
    public static final int MAX_KEYWORD_LENGTH = 50;

    private LikePattern() {
    }

    /**
     * Normalize keyword by trimming, converting empty values to null to mean no filtering, and truncating overlength values to at most {@value MAX_KEYWORD_LENGTH}.
     */
    public static String normalize(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_KEYWORD_LENGTH ? trimmed.substring(0, MAX_KEYWORD_LENGTH) : trimmed;
    }

    /**
     * Escape ILIKE wildcards so that %, _, and backslash match literally, requiring caller SQL to include ESCAPE '\'.
     */
    public static String escape(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /**
     * Normalize, escape, and wrap the keyword with percent signs to produce the ILIKE parameter value in one step, returning null for blank keywords to mean no filtering.
     */
    public static String toLikeParam(String keyword) {
        String normalized = normalize(keyword);
        return normalized == null ? null : "%" + escape(normalized) + "%";
    }
}
