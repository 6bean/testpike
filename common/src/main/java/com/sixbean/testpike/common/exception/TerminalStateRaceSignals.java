package com.sixbean.testpike.common.exception;

/**
 * Task write-back race vocabulary for background generation runs.
 * <p>When a task is stopped through the product stop door while its worker
 * is still in flight, the late write-back (start / done / failed) lands on a
 * row that has already reached a terminal state. The task state-machine
 * guards in the core task service reject it with
 * {@link BizErrorCode#STATE_MACHINE_REJECT}; that rejection is the expected
 * race outcome, never a persistence incident.
 * <p>Matching is structural: the rejection code AND one of the write-back
 * guard phrases. The phrase set mirrors exactly the guards in
 * {@code core/ai AiTaskServiceImpl} (its refusal wordings for the
 * cannot-report-done / cannot-report-failed / cannot-start-generation
 * write-backs) — the only STATE_MACHINE_REJECT producers that can reach
 * this seam. Other 40902 producers (case/device/defect state machines) do
 * not carry these phrases, so a genuine mid-run state rejection keeps its
 * normal failure write-back instead of being silently skipped: the demotion
 * fails loud (ERROR stays) when the wording drifts, never the other way.
 * <p>Same posture as {@link EntityGoneSignals} for the deleted-row family,
 * of which this class is the already-terminal sibling, not a copy (R40).
 */
public final class TerminalStateRaceSignals {

    /** Mirrors the AiTaskServiceImpl markDone/markFailed/markStart guards. */
    private static final String[] WRITE_BACK_RACE_PHRASES = {
            "无法回报完成", "无法回报失败", "无法开始生成",
    };

    private TerminalStateRaceSignals() {
    }

    /**
     * True when any {@link ServiceException} in the throwable's cause chain
     * carries the state-machine rejection code together with one of the
     * write-back guard phrases.
     */
    public static boolean matches(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof ServiceException e && e.getCode() != null
                    && e.getCode() == BizErrorCode.STATE_MACHINE_REJECT) {
                String msg = e.getMessage();
                if (msg == null) {
                    continue;
                }
                for (String phrase : WRITE_BACK_RACE_PHRASES) {
                    if (msg.contains(phrase)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
