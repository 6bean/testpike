package com.sixbean.testpike.core.analysis.domain.service;

import com.sixbean.testpike.core.analysis.api.dto.AssertionDensityView;
import com.sixbean.testpike.core.analysis.api.dto.CoverageSummaryView;

/**
 * Coverage-sufficiency read service for the AI conversation analysis channel
 * . Pure reads: no state machine side effects,
 * no audit writes, plan exit check untouched.
 * <p>All numbers reuse existing single sources — the J4 coverage matrix for
 * requirement coverage, the K8 exit-floor key plus the inventory read path for
 * contract coverage, and the active-case scope of the matrix for assertion
 * density. This service must never open a second denominator.
 */
public interface SufficiencyAnalysisService {

    /**
     * Requirement-coverage summary of one project (optionally narrowed to a
     * chapter / end type), with the contract-coverage exit gate projected
     * as plain information for the conversation.
     */
    CoverageSummaryView coverageSummary(long projectId, String chapter, String endType);

    /**
     * Assertion density over the latest version of every active case of one
     * project (optionally narrowed to an end type).
     */
    AssertionDensityView assertionDensity(long projectId, String endType);
}
