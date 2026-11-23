package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.api.dto.EvolutionTrendView;

/**
 * Trend side of the self-evolution loop (AF-i phase 4): compares the
 * observation rollup of a proposal run window against the next natural month
 * and reports whether each applied prompt-family proposal moved the numbers.
 * <p>Red lines this door holds by construction:
 * <ul>
 *   <li>single observation spec — both sides of the comparison are the same
 *       {@link EvolutionSignalService#observe} rollup the run reads; no new
 *       observation table or parallel metric exists;</li>
 *   <li>no silent pile-up — an ineffective verdict is stamped onto the draft
 *       row's context json ({@code trendVerdict} + the compared month) so the
 *       card itself carries the outcome, and a later recompute replaces the
 *       stale mark from live ledgers rather than trusting the old one;</li>
 *   <li>honest empty windows — a comparison month that has not completed
 *       stays "watching" and writes nothing; a month without any sealed
 *       answer stays "insufficient" instead of pretending a verdict;</li>
 *   <li>read + mark only — the report never lands, rolls back or publishes
 *       anything; those doors stay where they are.</li>
 * </ul>
 */
public interface EvolutionTrendService {

    /**
 * Report the before/after trend for one run window.
 *
 * @param month exact yyyy-MM run window key (fail-closed otherwise)
 * @param projectId project segment the run landed in (mandatory — the cards
 * are anchored to the project's evolution session)
 * @return the verdict rows for every APPLIED prompt-family card of the run
 */
    EvolutionTrendView trend(String month, Long projectId);
}
