package com.sixbean.testpike.core.mock.domain.component;

/**
 * Normalized paging window value object for the mock management plane (AG
 * . One place owns the clamp dialect shared by every paged
 * window (hit ledger, stub list, scenario list): page never below 1,
 * pageSize never below 1 and never above the cap — nonsense windows are
 * clamped and echoed back truthfully instead of refused, so a mis-typed URL
 * shows data rather than an error page (same posture the hit-ledger window
 * froze in ).
 *
 * @param page 1-based page index actually served (after clamping)
 * @param size window size actually served (after clamping)
 * @param offset row offset for SQL (derived, never trusted from the wire)
 */
public record PageWindow(int page, int size, int offset) {

    /** Shared cap — one window must never be a way to dump a whole table. */
    public static final int MAX_PAGE_SIZE = 500;

    /** Clamp a requested window into the legal range. */
    public static PageWindow of(int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(1, pageSize), MAX_PAGE_SIZE);
        return new PageWindow(safePage, safeSize, (safePage - 1) * safeSize);
    }
}
