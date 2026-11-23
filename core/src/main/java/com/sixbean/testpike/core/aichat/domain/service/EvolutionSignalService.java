package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.EvolutionSignalView;

/**
 * Read-only observation face of the self-evolution loop (AF-i phase 2):
 * aggregates the follow-up rate, thumbs ledger, error turns and tool-failure
 * distribution for one natural month, recomputed from existing ledgers.
 */
public interface EvolutionSignalService {

    /**
 * Aggregate the window's signals.
 *
 * @param month exact yyyy-MM window key, refused fail-closed otherwise
 * @param projectId project segment for the dialog signals, null = all projects
 * @return the rollup the proposal generators reason about
 */
    EvolutionSignalView observe(String month, Long projectId);
}
