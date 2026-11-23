package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;

/**
 * Ledger-backed implementation of the stream observation port (AO batch
 * : every moment the streaming ReAct loop emits is written through the
 * core internal door into the replayable frame ledger, so a reconnecting
 * browser (Last-Event-ID) and the abort command see exactly what the live
 * client saw.
 * <p>Mapping (the adjudication document's frame protocol): {@link #onDelta} →
 * {@code delta}, {@link #onReason} → {@code reason}, {@link #onToolStart}/
 * {@link #onToolDone} → {@code trace} with a meta phase marker. One sink
 * instance is bound to one turn (session + message id fixed at construction),
 * which is why the ledger calls stay parameter-light.
 * <p>Fail-closed by design: a ledger write failure propagates to the caller —
 * output that could not be journaled must not keep streaming as if it had
 * (the orchestrator then closes the turn as an honest error frame). Heartbeats
 * never pass through this class: they are transport-only and must not consume
 * a ledger seq.
 */
public class FrameLedgerStreamSink implements ReActStreamSink {

    /**
 * The one read tool whose answer is structured client-facing data (AO batch
 * ): the case flow guide. Its JSON body is journaled onto the trace
 * frame meta under {@code guide} so the dialog can render the action
 * buttons from what the ledger already holds — the same answer the model
 * saw, never a second fetch and never a re-faked one.
 */
    static final String TOOL_CASE_FLOW_GUIDE = "case.flow-guide";

    /** Structural check only; the body itself is journalled verbatim. */
    private static final ObjectMapper GUIDE_READER = new ObjectMapper();

    private final CoreGateway gateway;
    private final long sessionId;
    private final long messageId;
    /** Steering read door wired by the dialog orchestrator ; null = never polled. */
    private final UserInjectReader injectReader;
    /** Goal-plan finish door wired by the dialog orchestrator ; null = legacy finish. */
    private final ReActFinishReconciler finishReconciler;
    /** Abort read door wired by the dialog orchestrator ; null = never polled. */
    private final java.util.function.BooleanSupplier abortDoor;
    /**
 * Cooperative cancel signal : the shared single-source component
 * (R40) carries the abort once observed, so later checkpoint reads are
 * volatile-cheap and the door is never re-polled after the trip.
 */
    private final com.sixbean.testpike.aiservice.llm.domain.component.RunBudgetAbort runAbort =
            new com.sixbean.testpike.aiservice.llm.domain.component.RunBudgetAbort();
    /**
     * Every answer delta streamed since the last {@link #takeTurnAnswer()} —
     * the whole turn text the attribution guard must read (the self-report
     * line rides an earlier tool round in the live FC shape, not the closer).
     */
    private final StringBuilder turnAnswer = new StringBuilder();

    public FrameLedgerStreamSink(CoreGateway gateway, long sessionId, long messageId) {
        this(gateway, sessionId, messageId, null, null);
    }

    public FrameLedgerStreamSink(CoreGateway gateway, long sessionId, long messageId,
                                 UserInjectReader injectReader) {
        this(gateway, sessionId, messageId, injectReader, null);
    }

    public FrameLedgerStreamSink(CoreGateway gateway, long sessionId, long messageId,
                                 UserInjectReader injectReader,
                                 ReActFinishReconciler finishReconciler) {
        this(gateway, sessionId, messageId, injectReader, finishReconciler, null);
    }

    public FrameLedgerStreamSink(CoreGateway gateway, long sessionId, long messageId,
                                 UserInjectReader injectReader,
                                 ReActFinishReconciler finishReconciler,
                                 java.util.function.BooleanSupplier abortDoor) {
        this.gateway = gateway;
        this.sessionId = sessionId;
        this.messageId = messageId;
        this.injectReader = injectReader;
        this.finishReconciler = finishReconciler;
        this.abortDoor = abortDoor;
    }

    @Override
    public boolean aborted() {
        if (runAbort.aborted()) {
            return true; // one trip wins — later checkpoint reads stay door-free
        }
        if (abortDoor == null) {
            return false;
        }
        boolean sealedByAbort;
        try {
            sealedByAbort = abortDoor.getAsBoolean();
        } catch (RuntimeException doorDown) {
            // A momentarily unreachable read door must not kill a running
            // turn (same posture as the steering door): the next checkpoint
            // retries, the ledger state is durable in core anyway.
            return false;
        }
        if (sealedByAbort) {
            runAbort.trip("chat-turn-aborted-by-user");
        }
        return runAbort.aborted();
    }

    @Override
    public ReActFinishReconciler finishReconciler() {
        return finishReconciler;
    }

    @Override
    public java.util.List<String> takeSteeringTexts() {
        return injectReader == null ? java.util.List.of() : injectReader.poll();
    }

    @Override
    public void onUserInject(String text, int stepNo) {
        // Consumption marker on the assistant trajectory: the payload carries
        // the injected text verbatim (the user_inject frame core already
        // journaled proves WHAT, this trace frame proves WHEN — meta stepNo
        // = the ReAct step that picked it up; phase + stepNo let the client
        // render the human line from the ledger alone).
        gateway.appendChatFrame(sessionId, messageId, "trace", text,
                "{\"phase\":\"user_inject\",\"stepNo\":" + stepNo + "}");
    }

    @Override
    public void onDelta(String text) {
        gateway.appendChatFrame(sessionId, messageId, "delta", text, null);
        if (text != null) {
            synchronized (turnAnswer) {
                turnAnswer.append(text);
            }
        }
    }

    @Override
    public String takeTurnAnswer() {
        synchronized (turnAnswer) {
            String taken = turnAnswer.toString();
            turnAnswer.setLength(0);
            return taken;
        }
    }

    @Override
    public void onReason(String text) {
        gateway.appendChatFrame(sessionId, messageId, "reason", text, null);
    }

    @Override
    public void onToolStart(String toolName) {
        gateway.appendChatFrame(sessionId, messageId, "trace",
                "正在调用工具 " + toolName, traceMeta(toolName, "tool_start", null, null, null));
    }

    @Override
    public void onToolDone(String toolName, boolean success, String output) {
        gateway.appendChatFrame(sessionId, messageId, "trace",
                (success ? "工具 " + toolName + " 执行完成" : "工具 " + toolName + " 未执行（被拒绝或失败）"),
                traceMeta(toolName, "tool_done", success,
                        metaKeyOf(toolName), structuredEmbedding(toolName, success, output)));
    }

    /**
 * Which meta key carries this tool's structured answer: the case flow
 * guide journals under {@code guide} (action buttons, ), the built-in
 * aggregate query under {@code chart} (live card, P3). Every other
 * tool returns null = no embedding (its raw output stays model-only
 * content, already in the tool message).
 */
    private static String metaKeyOf(String toolName) {
        if (TOOL_CASE_FLOW_GUIDE.equals(toolName)) {
            return "guide";
        }
        return AggregateQueryTool.NAME.equals(toolName) ? "chart" : null;
    }

    /**
     * Journal the tool answer onto the trace meta only for the tools whose
     * output is client-facing structured data (see {@link #metaKeyOf}), only
     * on success, and only when the body really parses as a JSON object — an
     * unparseable body must not break the jsonb ledger column.
     */
    private static String structuredEmbedding(String toolName, boolean success, String output) {
        if (metaKeyOf(toolName) == null || !success || output == null || output.isBlank()) {
            return null;
        }
        try {
            var node = GUIDE_READER.readTree(output);
            return node != null && node.isObject() ? node.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Trace meta as a flat JSON object written by hand — the payload carries
     * the human-readable line, the meta carries the machine-readable phase and
     * tool name the client uses to fold the trajectory. No JSON library enters
     * this hot path just for three scalar fields (tool names may embed quotes;
     * they are escaped here). The structured answer, when present, is spliced
     * in as a nested value under {@code embeddingKey} (guide / chart — see
     * {@link #metaKeyOf}).
     */
    private static String traceMeta(String toolName, String phase, Boolean success,
                                    String embeddingKey, String embeddedJson) {
        StringBuilder meta = new StringBuilder(64)
                .append("{\"phase\":\"").append(phase)
                .append("\",\"tool\":\"").append(escape(toolName)).append('"');
        if (success != null) {
            meta.append(",\"success\":").append(success);
        }
        if (embeddedJson != null && embeddingKey != null) {
            meta.append(",\"").append(embeddingKey).append("\":").append(embeddedJson);
        }
        return meta.append('}').toString();
    }

    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
