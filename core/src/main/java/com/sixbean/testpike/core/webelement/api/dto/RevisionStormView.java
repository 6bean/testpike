package com.sixbean.testpike.core.webelement.api.dto;

import java.util.List;

/**
 * One revision-storm entry for a page (WE S7 A7): elements of the same page
 * that entered the stale state inside the rolling storm window, plus the
 * elements the step-health account currently marks about to break. A page
 * appears here only when at least one signal crosses the configured entry
 * count; the two signals are reported side by side and never pooled, so the
 * UI can tell "already broke" from "about to break" on the same card.
 */
public record RevisionStormView(String pageKey,
                                List<Long> staleElementIds,
                                List<Long> aboutToBreakElementIds) {

    public boolean staleStorm() {
        return staleElementIds.size() >= 1 && aboutToBreakElementIds.isEmpty();
    }
}
