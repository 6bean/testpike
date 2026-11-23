package com.sixbean.testpike.aiservice.tool.domain.component;

/**
 * Observation port of the streaming ReAct run : the
 * loop pushes every user-visible moment here as it happens, and the dialog
 * orchestrator maps each callback onto its SSE frame kind —
 * {@link #onDelta}→delta, {@link #onReason}→reason, {@link #onToolStart}/
 * {@link #onToolDone}→trace (tool_start/tool_done). Kept as a plain port (not
 * frame types) so this module stays independent of the core ledger: the
 * generator pipelines can pass {@code null} and get non-streaming behavior
 * with zero drift. Independent interface file — no inner types (code rules).
 */
public interface ReActStreamSink {

    /** Final-answer token delta, in provider order. */
    void onDelta(String text);

    /** Provider thinking delta — only fires when the model actually emits reasoning (never fabricated). */
    void onReason(String text);

    /** A dispatched tool call is about to execute (name = model-reported tool name). */
    void onToolStart(String toolName);

    /**
     * The dispatch finished: success=true executed, false = refused/failed (the
     * reason text still lands in the tool message). {@code output} = the same
     * content that goes back to the model — the dialog sink may surface a
     * structured answer of a client-facing read tool (flow guide) onto the
     * replayable frame ledger so the browser can render action buttons from
     * the journaled trace, never re-faked.
     */
    void onToolDone(String toolName, boolean success, String output);

    /**
 * Steering poll at a round boundary : texts the user
 * injected into this streaming turn since the last boundary. The base
 * answer is "nobody steered" — only the dialog ledger sink wired with a
 * {@link UserInjectReader} ever returns anything, so generator pipelines
 * and un-wired sinks keep the exact pre-steering trajectory.
 */
    default java.util.List<String> takeSteeringTexts() {
        return java.util.List.of();
    }

    /**
     * A steering text was consumed: it joined the model conversation as a user
     * message at step {@code stepNo} (= rounds + 1). The dialog sink journals
     * a trace marker so replay shows where in the trajectory the supplement
     * landed, next to the verbatim injected text.
     */
    void onUserInject(String text, int stepNo);

    /**
     * Attribution read seam (goal-lifecycle guard): the answer text streamed
     * through {@link #onDelta} since the last take, or {@code null} when this
     * sink does not accumulate. Taking resets the window, so each turn's
     * carry decision reads exactly that turn's streamed answer — the live FC
     * shape writes the per-round self-report line on an earlier tool round
     * while the sealed final round is only a short closer. Sinks without a
     * ledger buffer keep the caller's legacy reading path untouched.
     */
    default String takeTurnAnswer() {
        return null;
    }

    /**
 * Plain-text finish door seam : the dialog sink may expose
 * a goal-plan reconciler; every other surface answers null, keeping the
 * finish branch byte-identical legacy behaviour.
 */
    default ReActFinishReconciler finishReconciler() {
        return null;
    }

    /**
 * Chat-abort checkpoint door : true once the user's abort command
 * has sealed this turn's ledger in core. The base answer is "not
 * aborted" — only the dialog ledger sink wired with an abort read door
 * ever trips, so generator pipelines and un-wired sinks keep the exact
 * pre-abort trajectory.
 */
    default boolean aborted() {
        return false;
    }
}
