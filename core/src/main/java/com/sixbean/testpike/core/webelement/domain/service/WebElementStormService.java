package com.sixbean.testpike.core.webelement.domain.service;

import com.sixbean.testpike.core.webelement.api.dto.RevisionStormView;

import java.util.List;

/**
 * Revision-storm aggregation (WE S7 A7). A page whose elements went stale
 * together inside the rolling window (same source as the "N about to break
 * on one page" early warning) is one revision event, and the batch
 * trial-run suggestion is offered per event, never per element.
 */
public interface WebElementStormService {

    /**
     * Append-only account of a stale transition, called from the single
     * state-change choke point. Also prunes this project's rows past the
     * account horizon (window-hours * 4) in the same call — the creation
     * point carries its own bounded lifetime (R57).
     */
    void recordStaleTransition(long projectId, String pageKey, long elementId);

    /**
     * Current revision storms of a project: pages where the stale-event
     * count inside the rolling window, or the about-to-break count from the
     * step-health account, reaches the configured entry count. Reads are
     * pure SQL plus the health service — zero model calls, no second drift
     * ladder.
     */
    List<RevisionStormView> activeStorms(long projectId);
}
