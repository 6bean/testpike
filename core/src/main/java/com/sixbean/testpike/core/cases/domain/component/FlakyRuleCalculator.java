package com.sixbean.testpike.core.cases.domain.component;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Flaky window flip determination algorithm for the Flaky semantics in the requirements document.
 * <p>Definition: take the latest {@value #WINDOW} execution results and count pass↔fail flips.
 * (tp_exec_task.status 3=success, 4=failure, and 5=timeout are all counted on the failure side; non-terminal states such as 6=skip do not participate);
 * When flips are at least {@value #FLIP_THRESHOLD} and window samples are at least {@value #MIN_SAMPLES} (insufficient samples are not judged), mark as flaky.
 * <p>Clear suggestion (non-automatic): the currently isolated case has {@value #CLEAR_STREAK} recent consecutive consistent results.
 * (all success or all failure) → suggestClear, prompting manual clearing.
 * <p>Stateless pure-function component (directly instantiated in L2 tests; reused by the detection task {@link com.sixbean.testpike.core.cases.domain.service.FlakyDetectionService}).
 *
 */
@Component
public class FlakyRuleCalculator {

    /** Observation window: the latest N executions. */
    static final int WINDOW = 10;
    /** Flip threshold: the case is marked flaky when flips in the window are at least this count. */
    static final int FLIP_THRESHOLD = 2;
 /** Minimum sample count: the case is not judged when samples in the window are fewer than this count. */
    static final int MIN_SAMPLES = 3;
 /** Clear suggestion: number of recent consecutive consistent results for isolated cases (non-automatic). */
    static final int CLEAR_STREAK = 5;

    // tp_exec_task.status: 3 success / 4 failure / 5 timeout (failure side).
    private static final int STATUS_SUCCESS = 3;

    /**
     * Judge a single test case.
     *
     * @param statuses status sequence of execution results for this case (chronological order, old→new; contains only terminal states 3/4/5, filtered by the caller).
     * @param currentlyFlaky whether the case is currently isolated (flaky=1); suggestClear is meaningful only for it.
     * @return judgment result (flaky/flips/samples/reason/suggestClear).
     */
    public FlakyVerdict evaluate(List<Integer> statuses, boolean currentlyFlaky) {
        List<Integer> window = statuses.size() > WINDOW
                ? statuses.subList(statuses.size() - WINDOW, statuses.size())
                : statuses;
        int flips = 0;
        for (int i = 1; i < window.size(); i++) {
            if (side(window.get(i - 1)) != side(window.get(i))) {
                flips++;
            }
        }
        boolean flaky = window.size() >= MIN_SAMPLES && flips >= FLIP_THRESHOLD;
        String reason = flaky
                ? "近 " + WINDOW + " 次执行结果翻转 " + flips + " 次（通过↔失败），判定为不稳定用例"
                : null;
        boolean suggestClear = currentlyFlaky && trailingConsistent(window) >= CLEAR_STREAK;
        return new FlakyVerdict(flaky, flips, window.size(), reason, suggestClear);
    }

    /** Result side attribution: success = pass side (0), failure/timeout = failure side (1). */
    private static int side(int status) {
        return status == STATUS_SUCCESS ? 0 : 1;
    }

    /** Count consecutive results on the same side from the latest execution backward. */
    private static int trailingConsistent(List<Integer> window) {
        if (window.isEmpty()) {
            return 0;
        }
        int lastSide = side(window.get(window.size() - 1));
        int streak = 0;
        for (int i = window.size() - 1; i >= 0 && side(window.get(i)) == lastSide; i--) {
            streak++;
        }
        return streak;
    }
}
