package com.sixbean.testpike.core.cases.domain.component;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Flaky-probe outcome judgement for the re-probe loop (AO batch , closed-loop
 * operations S2: isolate → re-probe on a schedule → propose restoring after N greens).
 * <p>Stateless pure function on purpose, so the rule is directly testable in L2 without a
 * database and is reusable by the probe sweep service. It mirrors the window semantics of
 * {@link FlakyRuleCalculator} (terminal statuses only, success = green, failure and timeout =
 * the red side) and keeps the same bar: the default threshold comes from the hot-updatable key
 * {@code ai.flaky.probe.green-count} and matches the five-consistent-results bar the daily
 * detection already uses for its clear suggestion, so the loop never runs two different rules.
 * <p>Nothing here touches a case row: proposing is a suggestion, clearing a quarantine stays a
 * human action behind the confirmation door (one-directional door, unchanged).
 */
@Component
public class FlakyProbeCalculator {

    // tp_exec_task.status: 3 success / 4 failure / 5 timeout (failure side), same mapping as
    // FlakyRuleCalculator - 6 (skipped) and any non-terminal status are not an outcome yet.
    private static final int STATUS_SUCCESS = 3;
    private static final int STATUS_FAILURE = 4;
    private static final int STATUS_TIMEOUT = 5;

    /**
     * Fold newly observed results into the held streak of one probe session.
     *
     * @param statuses       terminal status sequence of the newly consumed executions for this
     *                       case, chronological (old → new); non-terminal entries are skipped
     * @param heldStreak     the streak already recorded on the probe session row
     * @param alreadyProposed whether a restore proposal is still pending confirmation for this
     *                        session (suppresses a second proposal for the same crossing)
     * @param greenThreshold N consecutive greens required to propose restoring; read per sweep
     *                        from {@code ai.flaky.probe.green-count}, never a constant here - a
     *                        non-positive value (misconfigured key) proposes nothing
     * @return folded streak plus the propose / withdraw decision for this sweep
     */
    public FlakyProbeDecision evaluate(List<Integer> statuses, int heldStreak,
                                       boolean alreadyProposed, int greenThreshold) {
        int streak = Math.max(heldStreak, 0);
        int terminalCount = 0;
        boolean sawFailure = false;
        if (statuses != null) {
            for (Integer status : statuses) {
                if (status == null || !isTerminal(status)) {
                    continue;
                }
                terminalCount++;
                if (status == STATUS_SUCCESS) {
                    streak++;
                } else {
                    streak = 0;
                    sawFailure = true;
                }
            }
        }
        boolean thresholdReached = greenThreshold > 0 && streak >= greenThreshold;
        boolean proposeRestore = thresholdReached && !alreadyProposed;
        boolean withdrewProposal = alreadyProposed && sawFailure;
        return new FlakyProbeDecision(streak, terminalCount, proposeRestore, withdrewProposal);
    }

    /** Whether a status is a final outcome that can be counted (failure and timeout both are). */
    private static boolean isTerminal(int status) {
        return status == STATUS_SUCCESS || status == STATUS_FAILURE || status == STATUS_TIMEOUT;
    }
}
