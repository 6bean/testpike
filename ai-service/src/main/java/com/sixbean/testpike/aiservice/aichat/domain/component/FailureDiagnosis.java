package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * Outcome of fetching the failure diagnosis behind a badge-deep-link execution
 * pointer . Three shapes only:
 * <ul>
 * <li>{@link #NONE} — the question pinned no execution at all: the turn
 * behaves exactly as before this feature;</li>
 * <li>{@link #degraded} — a pointer exists but its row could not be read
 * (vanished task, core door down): the turn still runs, and one honest
 * state frame tells the user the diagnosis is missing, because silently
 * answering "why did it fail" without the failure would be a fabrication;</li>
 * <li>{@link #refused} — the pointer names another project's task: a
 * horizontal-access attempt (H1 red line), so the run is refused with the
 * id named, never silently answered from a leaked diagnosis;</li>
 * <li>{@link #resolved} — the diagnosis block rides the system prompt.</li>
 * </ul>
 * <p>Knife 3a widens the resolved shape with the anchors the repair-proposal
 * draft needs (which execution, which case, the raw error detail) — carried
 * through instead of re-read, so the proposal consumes exactly the diagnosis
 * the user is looking at, nothing fresher or staler.
 *
 * @param rejectionReason non-null only when the run must be refused
 * @param absentReason non-null only when the diagnosis could not be read
 * @param contextBlock diagnosis text for the system prompt ("" = none)
 * @param executionId task the pointer names (0 when none)
 * @param caseId case behind the task (draft card anchor, 0 when none)
 * @param errorDetail raw failure detail (proposal input, null when none)
 */
public record FailureDiagnosis(String rejectionReason, String absentReason, String contextBlock,
                               long executionId, long caseId, String errorDetail) {

    /** No execution pointer on this turn. */
    public static final FailureDiagnosis NONE =
            new FailureDiagnosis(null, null, "", 0L, 0L, null);

    /** Pointer present and verified: the digest block rides the prompt. */
    public static FailureDiagnosis resolved(long executionId, long caseId,
                                            String errorDetail, String block) {
        return new FailureDiagnosis(null, null, block, executionId, caseId, errorDetail);
    }

    /** Pointer present but unreadable: honest absence, the turn still runs. */
    public static FailureDiagnosis degraded(String reason) {
        return new FailureDiagnosis(null, reason, "", 0L, 0L, null);
    }

    /** Pointer crosses the project segment: refuse the run, naming the id. */
    public static FailureDiagnosis refused(String reason) {
        return new FailureDiagnosis(reason, null, "", 0L, 0L, null);
    }

    public boolean refused() {
        return rejectionReason != null;
    }

    public boolean degraded() {
        return absentReason != null;
    }

    /** A full, slice-verified diagnosis is on the table (draft material exists). */
    public boolean resolved() {
        return rejectionReason == null && absentReason == null
                && contextBlock != null && !contextBlock.isEmpty();
    }
}
