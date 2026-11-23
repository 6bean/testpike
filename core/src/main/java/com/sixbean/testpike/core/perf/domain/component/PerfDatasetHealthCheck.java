package com.sixbean.testpike.core.perf.domain.component;

/**
 * Dataset pool health wording (CF-7, expected consumption vs pool capacity).
 * <p>Exclusive row-allocation strategies hand every row out exactly once —
 * {@code SEQUENTIAL_EXCLUSIVE} leases rows in file order per request,
 * {@code VUSER_EXCLUSIVE} gives each virtual user a private segment — so both
 * consume one row per request and the demand of a plan is simply
 * peak-rate x duration. A pool smaller than the demand drains mid-run and the
 * remaining users get nothing; the operator should hear that BEFORE launching,
 * with the approximate drain moment and the remedy (switch to {@code CYCLE}
 * or upload a larger pool). {@code CYCLE} reuses rows round-robin and can
 * never stall, which is annotated rather than treated as a problem.
 * <p>Nothing is guessed: an unknown/null strategy spelling, a non-positive
 * rate or duration (LADDER-style plans without a derivable tier keep their
 * own semantics elsewhere), or demand within the pool all yield null = no
 * claim. The run-time mid-press drain stop with the named moment lives on the
 * executor side (perf_worker row allocator); this class is the pre-launch
 * projection of the same arithmetic.
 */
public final class PerfDatasetHealthCheck {

    /**
     * Pre-launch health wording for the bound pool, or null when there is
     * nothing honest to say (no pool, no claim, or the plan fits).
     *
     * @param rowAllocMode      resolved allocation strategy (launch-pinned value
     *                          preferred — same resolution order as the adapters)
     * @param poolRowCount      rows currently in the bound dataset
     * @param peakRate          peak concurrency tier of the plan (same derivation
     *                          the launch gate uses)
     * @param durationSeconds   declared wall-clock of the plan
     */
    public static String noteOf(String rowAllocMode, long poolRowCount,
                                double peakRate, double durationSeconds) {
        if (PerfRowAllocModes.CYCLE.equals(rowAllocMode)) {
            return "数据池按循环复用分配：行用尽后从头循环，长跑不会耗尽数据池。";
        }
        if (!PerfRowAllocModes.SEQUENTIAL_EXCLUSIVE.equals(rowAllocMode)
                && !PerfRowAllocModes.VUSER_EXCLUSIVE.equals(rowAllocMode)) {
            return null;   // null/unknown spelling = no claim (save guard owns legitimacy)
        }
        if (peakRate <= 0 || durationSeconds <= 0 || poolRowCount <= 0) {
            return null;   // undecidable shape = silent card, never a fabricated demand
        }
        long demand = (long) Math.ceil(peakRate * durationSeconds);
        if (demand <= poolRowCount) {
            return null;   // pool covers the plan
        }
        long drainSeconds = (long) Math.floor(poolRowCount / peakRate);
        return "按独占分配口径，本次压测约消耗 " + demand + " 行数据，超过数据池 "
                + poolRowCount + " 行：预计约 " + drainSeconds + " 秒后数据池耗尽，"
                + "之后新请求将无数据可用并提前停止。可改用循环复用分配，或扩充数据池后重试。";
    }

    private PerfDatasetHealthCheck() {
    }
}
