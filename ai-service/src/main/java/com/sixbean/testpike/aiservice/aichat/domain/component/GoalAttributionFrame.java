package com.sixbean.testpike.aiservice.aichat.domain.component;

/**
 * The three verdicts of the per-round goal-attribution self-report
 * (behaviour kernel round-2 item 1, the ledger's reverse guardrail): the
 * dialog model declares on every final answer whether the round continues
 * the session's open goal or opens a new one.
 *
 * <ul>
 *   <li>{@link #CONTINUE_OLD} — this round belongs to the open ledger's
 *       goal (including a goal the round itself just planned): the ledger
 *       may be carried on;</li>
 *   <li>{@link #OPEN_NEW} — this round is a topic switch: the old ledger is
 *       closed with a stored trace and NOTHING of it may be inherited into
 *       the new answer;</li>
 *   <li>{@link #FRAME_MISSING} — no well-formed declaration line: treated
 *       exactly like a fresh unrelated round for carrying purposes — the
 *       old ledger is NOT loaded (fail-safe direction pinned by the
 *       ruling: losing continuity is preferred over polluting
 *       correctness).</li>
 * </ul>
 */
public enum GoalAttributionFrame {
    CONTINUE_OLD,
    OPEN_NEW,
    FRAME_MISSING
}
