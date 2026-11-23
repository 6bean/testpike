package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.cases.api.dto.FlakyProbeSweepResult;
import com.sixbean.testpike.core.cases.api.dto.FlakyRestoreProposal;

import java.util.List;

/**
 * Flaky re-probe ledger service .
 * <p>One sweep = open sessions for quarantined cases (idempotent), close sessions whose
 * case left quarantine, fold post-quarantine terminal results through the per-session
 * cursor, raise a restore proposal (state 1 + one notification) at the configured green
 * threshold, and withdraw a pending proposal when a new failure lands. The door is
 * one-directional: this service never clears tp_test_case.flaky - restoring stays a
 * human confirmation on the cases page.
 */
public interface FlakyProbeService {

    /** Runs one full ledger round; hot keys are re-read per call, so tuning takes effect live. */
    FlakyProbeSweepResult sweep();

    /**
 * Lists the project's pending restore proposals (state 1 sessions) for the cases page
 * read channel). Strictly zero-write: confirming a restore stays a human
 * action through the existing PUT /cases/{id}/flaky door. The green threshold rides
 * the live hot-key value re-read per call, so retuning shows up without a sweep.
 */
    List<FlakyRestoreProposal> pendingProposals(long projectId);
}
