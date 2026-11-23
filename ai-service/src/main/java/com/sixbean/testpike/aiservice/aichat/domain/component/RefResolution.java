package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * Outcome of resolving the @-mention chips of one dialog question (AO batch
 * knife 4c). Two shapes only: a resolved context block (possibly empty —
 * no chips at all), or a refusal carrying the human-readable reason naming
 * the offending chip.
 * <p>Refusal posture is fail-closed by adjudication: a chip the user pinned
 * that cannot be verified (row vanished, other project's row = horizontal
 * access attempt, core door down) must NOT silently degrade into an answer
 * that ignores it — the answer would differ from what the user pinned. The
 * run is refused with the reason journaled as an error frame instead.
 *
 * @param rejectionReason non-null only when the run must be refused
 * @param contextBlock line-per-chip digest for the system prompt ("" = none)
 */
public record RefResolution(String rejectionReason, String contextBlock) {

    /** No chips pinned: no block, no refusal. */
    public static final RefResolution EMPTY = new RefResolution(null, "");

    /** Chips pinned and all verified: the digest block rides the prompt. */
    public static RefResolution resolved(String block) {
        return new RefResolution(null, block);
    }

    /** A chip could not be verified: refuse the run, naming it in the reason. */
    public static RefResolution refused(String reason) {
        return new RefResolution(reason, null);
    }

    public boolean refused() {
        return rejectionReason != null;
    }
}
