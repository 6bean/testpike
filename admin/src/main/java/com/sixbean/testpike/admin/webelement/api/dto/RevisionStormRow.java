package com.sixbean.testpike.admin.webelement.api.dto;

import java.util.List;

/**
 * One revision-storm card for the board (mirror of the core aggregation):
 * elements that already went stale together in the rolling window plus the
 * elements currently marked about to break. The two lists stay side by side
 * and are never pooled — the page must tell "already broke" apart from
 * "about to break".
 */
public record RevisionStormRow(String pageKey, List<Long> staleElementIds,
                               List<Long> aboutToBreakElementIds) {
}
