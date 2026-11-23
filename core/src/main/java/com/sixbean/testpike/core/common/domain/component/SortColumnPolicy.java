package com.sixbean.testpike.core.common.domain.component;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.util.Map;
import java.util.TreeSet;

/**
 * List sort column whitelist strategy (cross-domain shared component, same origin, copy prohibited).
 * <p>Two modes: {@link #orDefault} silently falls back to the default column for invalid values (existing use-case list semantics);
 * {@link #require} returns a 400 with a human-readable message for invalid or injected column names (stricter mode for ecosystem interfaces).
 * Whitelist values must be physical column names; both methods return values from a Map, and user input never enters SQL concatenation.
 *
 */
public final class SortColumnPolicy {

    private SortColumnPolicy() {
    }

    /** Invalid sort columns fall back to the default column (existing semantics; no change in caller behavior). */
    public static String orDefault(String sortBy, Map<String, String> whitelist, String defaultColumn) {
        return whitelist.getOrDefault(sortBy == null ? "" : sortBy, defaultColumn);
    }

    /**
 * Invalid sort columns return a 400 with a human-readable message (rejecting SQL-injection column names and including the allowlist in the error).
     *
     * @param entityLabel Chinese display name of the entity (e.g., "SKILL"), interpolated into the error message.
     */
    public static String require(String sortBy, Map<String, String> whitelist, String entityLabel) {
        if (sortBy == null || sortBy.isBlank()) {
            return null; // Unspecified sort uses the caller's default sort.
        }
        String column = whitelist.get(sortBy);
        if (column == null) {
            throw new ServiceException(entityLabel + " 排序列「" + sortBy + "」不在白名单（可选："
                    + String.join("/", new TreeSet<>(whitelist.keySet())) + "）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return column;
    }
}
