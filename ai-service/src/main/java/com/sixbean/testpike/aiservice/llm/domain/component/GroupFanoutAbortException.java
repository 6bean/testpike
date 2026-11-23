package com.sixbean.testpike.aiservice.llm.domain.component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * — controlled stop thrown by {@link GroupParallelFanout} when a
 * {@link RunBudgetAbort} signal is tripped mid-run.
 *
 * <p>Vocabulary contract: this is a DISTINCT exception type from the
 * fail-closed face — a real task failure still propagates its original
 * RuntimeException UNWRAPPED (the generation-abort vocabulary upstream
 * relies on is untouched). Callers catching THIS type know the run ended by
 * budget-gate decision, not by output invalidity, and must attribute it as
 * such (the doc face folds it into the honest time-budget wording).
 *
 * <p>The exception carries the ordered partial result so no already-landed
 * work is lost on the propagation path: slot values completed before the
 * gate trip are kept, slots cancelled or never started are {@code null},
 * and the list length always equals the submitted task count.
 */
public final class GroupFanoutAbortException extends RuntimeException {

    private final List<?> landedInOrder;

    public GroupFanoutAbortException(String reason, List<?> landedInOrder) {
        super("组并行扇出被预算闸中止: " + reason);
        this.landedInOrder = Collections.unmodifiableList(new ArrayList<>(landedInOrder));
    }

    /** Results in task order; {@code null} marks cancelled/never-started slots. */
    @SuppressWarnings("unchecked")
    public <T> List<T> landedInOrder() {
        return (List<T>) landedInOrder;
    }
}
