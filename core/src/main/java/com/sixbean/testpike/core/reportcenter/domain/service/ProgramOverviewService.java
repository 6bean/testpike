package com.sixbean.testpike.core.reportcenter.domain.service;

import com.sixbean.testpike.core.reportcenter.api.dto.ProgramOverview;

/**
 * Live aggregation channel for the program (project-set) quality dashboard.
 *
 * <p>Distinct from snapshot generation: the dashboard evaluates calibers on demand
 * instead of reading stored tp_report rows, so the numbers always match the current
 * registry version.
 */
public interface ProgramOverviewService {

    /**
     * Aggregate one program through the lens visibility rules.
     *
     * @param programId  program (project set) id; must be positive, unknown id = 40401
     * @param operatorId caller; null = trusted internal call without a session user
     *                   (no personal narrowing, mirroring ProgramLensScope semantics),
     *                   a non-admin only ever sees projects inside their visible domain
     * @param periodFrom ISO LocalDateTime; null/blank = default (periodTo minus 7 days)
     * @param periodTo   ISO LocalDateTime; null/blank = default (now)
     */
    ProgramOverview overview(long programId, Long operatorId, String periodFrom, String periodTo);
}
