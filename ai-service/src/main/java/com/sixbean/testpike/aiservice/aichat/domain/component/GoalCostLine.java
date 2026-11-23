package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * Cost-transparency line rules of the goal-plan ledger card (AX batch
 * item "progress cost transparency", paired with §6: the consumed-tokens
 * number is the same ledger the prefix-economics view wants to operate —
 * cached-token hits only matter once users can SEE what a run already spent).
 * <p>Single source for the wording so the auto-batch loop's three sentences
 * (batch pickup, safety-cap stop, zero-open close) cannot drift apart, and
 * so the front end never re-computes a cost number: the figure rides the
 * frame, folded from {@code CoreGateway.sessionTotalTokens} — the identical
 * door the session budget gate and the E9 cost view (core
 * ChatUsageAttributionServiceImpl session_total_tokens) read, per R40.
 * <p>Shape contract with the front end (R40 consumer, never co-owned):
 * {@code chatDockReducer.parseGoalOpenTail} anchors on the pinned prefix
 * and needs no reducer change; anything that rewrites the prefix would
 * silently drop the card fold back to a plain process line.
 * <p>Deliberately I/O-free: -1 is the caller's "unknown consumption"
 * sentinel (door read failure — the same posture as the budget gate's
 * fail-open) and renders an honest cannot-read tail instead of a fabricated
 * zero, because a cost number that lies is worse than no cost number.
 */
public final class GoalCostLine {

    /** Honest tail when the consumption door could not answer this turn. */
    static final String UNKNOWN_TAIL = "；本会话消耗量本次未能读取。";
    /** Cost tail carrying the real session consumption. */
    static final String KNOWN_TAIL_PREFIX = "；本会话已消耗 ";
    static final String KNOWN_TAIL_SUFFIX = " tokens。";

    private GoalCostLine() {
    }

    /**
     * Append the cost tail to a ledger sentence: the sentence's closing
     * full stop moves to the tail (one sentence, one terminator), and the
     * pinned open-tail prefix in front of it is never touched. A null or
     * blank sentence stays null — there is nothing to annotate.
     */
    public static String appendCostTail(String sentence, long consumedTokens) {
        if (sentence == null || sentence.isBlank()) {
            return sentence == null ? null : sentence;
        }
        String stem = sentence.endsWith("。")
                ? sentence.substring(0, sentence.length() - 1)
                : sentence;
        return consumedTokens >= 0
                ? stem + KNOWN_TAIL_PREFIX + consumedTokens + KNOWN_TAIL_SUFFIX
                : stem + UNKNOWN_TAIL;
    }

    /**
     * Standalone progress line (tokens consumed + steps still open) for
     * channels that show the ledger without the batch-loop sentence — the
     * count semantics come from {@link GoalPlanLedger#pendingCount} so the
     * "remaining" figure is the same open-tail the resume block names.
     */
    public static String progressLine(long consumedTokens, int remainingSteps) {
        return (consumedTokens >= 0
                ? "本会话已消耗 " + consumedTokens + " tokens"
                : "本会话消耗量本次未能读取")
                + "，剩余 " + remainingSteps + " 步未完成。";
    }
}
