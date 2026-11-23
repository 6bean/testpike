package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;

import java.util.List;

/**
 * Plain-text finish door of the ReAct loop . Before the loop
 * ends a run on a bare text answer, the dialog surface gets one chance to
 * reconcile that answer against the session's goal-plan ledger: the door
 * returns a reminder to inject as a system message (the loop keeps running)
 * or {@code null} to let the finish through.
 * <p>The per-run nudge budget lives in the door implementation, not in the
 * loop — a spent budget answers {@code null} on the next finish attempt, so
 * by the door's own state. The runner adds one more guard: a nudged round
 * counts against {@code ai.tool.fc-max-rounds}, so a misbehaving door can
 * never loop the model forever.
 */
public interface ReActFinishReconciler {

    /**
     * Called with the finished transcript when the model just answered with
     * plain text. Must not block on slow I/O beyond a database round trip
     * and must be fail-soft from the runner's point of view: any thrown
     * RuntimeException is logged and the finish proceeds untouched.
     *
     * @param transcript conversation up to this point (assistant/tool turns
     *                 included, so the door can locate a plan.propose call)
     * @return reminder text to feed back to the model, or null to finish
     */
    String takeFinishNudge(List<ChatMessage> transcript);

    /**
 * Suspension lane : called with the paused transcript
 * when the run ends on an accepted clarification card instead of a
 * plain-text answer. Unlike the finish lane there is no follow-up model
 * round to feed, so the door journals the transcript's open ledger (or
 * stays silent) and returns nothing; the nudge budget is only consulted
 * as an on/off door there, never spent. Fail-soft exactly like {@link
 * #takeFinishNudge}: any RuntimeException is logged and the suspension
 * stands. The default no-op keeps every non-dialog surface byte-identical.
 *
 * @param transcript conversation up to the suspension point, tool-result
 * pairs included
 */
    default void onRunSuspend(List<ChatMessage> transcript) {
    }
}
