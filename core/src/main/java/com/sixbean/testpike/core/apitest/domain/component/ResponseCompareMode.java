package com.sixbean.testpike.core.apitest.domain.component;

/**
 * Format tri-state decided by the read path before any comparison happens
 * (W-c5 run-history compare). The UI renders each mode differently:
 * <ul>
 *   <li>{@code JSON} — both bodies parse as JSON, four-color leaf diff applies;</li>
 *   <li>{@code TEXT} — at least one side is not parsable and neither contains
 *       NUL, line-level text diff applies;</li>
 *   <li>{@code BINARY} — a body carries U+0000, only SHA-256 digests are
 *       reported, raw bytes are never rendered.</li>
 * </ul>
 */
public enum ResponseCompareMode {
    JSON,
    TEXT,
    BINARY
}
