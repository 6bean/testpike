package com.sixbean.testpike.admin.aichat.app.service;

import com.sixbean.testpike.admin.aichat.api.dto.ChatFrameView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatInjectLanding;
import com.sixbean.testpike.admin.aichat.api.dto.ChatInjectView;
import com.sixbean.testpike.admin.aichat.app.component.StreamFrameWriter;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatObjectRef;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The dialog SSE pump : the single admin-side
 * place that turns the append-only frame ledger into a live stream.
 * <p>Posture per the frame-protocol adjudication (never leave the user
 * waiting): a fresh prompt opens exactly one run through the core door, then
 * every ledger frame is forwarded in seq order (SSE {@code id:} = seq so a
 * reconnect can resume from Last-Event-ID); silence fires heartbeat comment
 * frames on the K8 interval; the first-frame budget, the inter-frame idle
 * timeout and the terminal done/error frames each close the stream honestly —
 * budget/idle failures are injected as an error frame naming the timeout,
 * never a silent hang and never a fake completion.
 * <p>All three timeouts are hot-readable K8 keys resolved once per stream (a
 * long stream keeps the values it opened with; the next question sees the new
 * ones). {@link StreamFrameWriter} is the test seam: the pump itself is pure
 * logic, which is what lets the five spectra run at L2 against an in-memory
 * core fake while the live door is proven by L3.
 */
@Service
public class ChatStreamBridge {

    /** Ledger type of the closing frame — forwarding it ends the stream. */
    private static final String FRAME_DONE = "done";
    /** Ledger type of a degradation already explained by its writer. */
    private static final String FRAME_ERROR = "error";
    /** Throttle of the sealed-block probe on empty pulls (ms) — safety net, not the hot path. */
    private static final long SEAL_PROBE_INTERVAL_MILLIS = 500L;

    private final CoreAiChatClient coreClient;

    public ChatStreamBridge(CoreAiChatClient coreClient) {
        this.coreClient = coreClient;
    }

    /**
 * Serve one stream of one session for one user until done / error / budget.
 * Blocking by design — the controller runs this on an async pool.
 *
 * @param resumeCursor non-null for a reconnect (Last-Event-ID): replays only
 * frames after the cursor and never opens a second run.
 * @param refObjects @-mention chips of a fresh question (knife 4b); a
 * resume never carries them — the run already holds them.
 * @param attachments user-image uploads of a fresh question ( P4
 * ; likewise never carried on a resume.
 * @param systemManager vertical verdict from the login session face, relayed
 * untouched to the run door for the grounding trim map
 * ; a resume never needs it —
 * the run already carries its own verdict.
 * @param questionContext send-time page anchor of a fresh question (BE batch
 * ; relayed untouched, core's whitelist
 * door is the single validation point; a resume carries
 * none — the run it re-attaches to already holds its own.
 */
    public void stream(long sessionId, long userId, String prompt,
                       List<ChatObjectRef> refObjects,
                       List<ChatImageAttachment> attachments, Long resumeCursor,
                       StreamFrameWriter writer, boolean systemManager,
                       String questionContext) {
        int heartbeatSeconds = coreClient.readConfigInt("ai.chat.heartbeat-seconds", 10);
        int firstFrameBudgetSeconds = coreClient.readConfigInt("ai.chat.first-frame-budget-seconds", 5);
        int idleTimeoutSeconds = coreClient.readConfigInt("ai.chat.stream-idle-timeout-seconds", 60);
        long pollMillis = 20L;
        long heartbeatMillis = Math.max(1, heartbeatSeconds) * 1000L;
        long budgetMillis = Math.max(1, firstFrameBudgetSeconds) * 1000L;
        long idleMillis = Math.max(1, idleTimeoutSeconds) * 1000L;

        boolean resuming = resumeCursor != null;
        long cursor = resuming ? resumeCursor : 0L;
        // For a fresh run the assistant block id is the pump's safety net: even if
        // the worker seals the run before our first poll, those frames stay pulled.
        long runMessageId = resuming ? 0L
                : coreClient.startRun(sessionId, userId, prompt, refObjects, attachments,
                        systemManager, questionContext);

        long openedAt = System.currentTimeMillis();
        long lastActivityAt = openedAt;
        long lastBeatAt = 0L;
        boolean firstFrameSeen = resuming;
        // Sealed-block probe clock: start 0 so the very first empty pull probes at once.
        long lastSealedProbeAt = 0L;

        while (!writer.isAborted()) {
            List<ChatFrameView> frames = coreClient.replay(sessionId, userId, cursor);
            long now = System.currentTimeMillis();
            if (!frames.isEmpty()) {
                cursor = forward(frames, writer, cursor);
                String terminal = frames.get(frames.size() - 1).eventType();
                if (FRAME_DONE.equals(terminal) || FRAME_ERROR.equals(terminal)) {
                    writer.complete();
                    return;
                }
                lastActivityAt = now;
                firstFrameSeen = true;
            } else {
                long sinceActivity = now - lastActivityAt;
                boolean idleOverrun = sinceActivity >= idleMillis;
                boolean budgetOverrun = !firstFrameSeen && now - openedAt >= budgetMillis;
                // A fast worker (instant refusal, quick answer) can seal the whole
                // block before (or between) this pump's polls: once sealed, the
                // session-wide replay is empty forever while the ledger says
                // otherwise. Probe this run's block on empty pulls, throttled —
                // the real frames land in milliseconds instead of the pump
                // inventing a timeout the ledger disproves (live L3 defect,
                // spectra A/B: sealed-before-first-poll AND mid-stream sealing).
                if (runMessageId > 0 && now - lastSealedProbeAt >= SEAL_PROBE_INTERVAL_MILLIS) {
                    lastSealedProbeAt = now;
                    List<ChatFrameView> sealed = coreClient.replayRun(userId, runMessageId, cursor);
                    if (!sealed.isEmpty()) {
                        int terminalIndex = terminalIndexOf(sealed);
                        int upTo = terminalIndex >= 0 ? terminalIndex + 1 : sealed.size();
                        cursor = forward(sealed.subList(0, upTo), writer, cursor);
                        if (terminalIndex >= 0) {
                            writer.complete();
                            return;
                        }
                        lastActivityAt = System.currentTimeMillis();
                        firstFrameSeen = true;
                        continue;
                    }
                }
                if (idleOverrun || budgetOverrun) {
                    if (idleOverrun) {
                        injectTerminalError(writer, cursor + 1,
                                "等待新内容超时：自上一条消息后 " + idleTimeoutSeconds
                                        + " 秒内没有收到新的回答内容，可能是模型排队过长或本次回答已中断，可重新提问。");
                        return;
                    }
                    injectTerminalError(writer, cursor + 1,
                            "等待首个响应超时：" + firstFrameBudgetSeconds
                                    + " 秒内未收到任何回答内容，AI 服务可能繁忙或未启动，请稍后重试。");
                    return;
                }
                if (sinceActivity >= heartbeatMillis && now - lastBeatAt >= heartbeatMillis) {
                    // Keep-warm on its own clock: a comment frame is transport
                    // noise, not content — it must never reset the idle timer,
                    // or a heartbeat shorter than idle would make the timeout
                    // unreachable and a sealed/abandoned stream would hang to
                    // the emitter cap (live L3: spectrum C hung 30 minutes).
                    writer.writeHeartbeat();
                    lastBeatAt = now;
                }
            }
            sleep(pollMillis);
        }
    }

    /**
 * Steer the answer that is still streaming : hand the user's
 * supplement to the core steering door and report back what actually
 * happened. Landing is core's verdict — the text joined that running turn.
 * With no turn running the same text is submitted through the regular
 * message channel (the very door a fresh question uses, so no second
 * submission path appears here), never dropped. Refs and images never ride
 * a steering sentence: the chips belong to the question that opened the run.
 *
 * @param systemManager vertical verdict pinned once from the login session
 * face, relayed untouched to the degraded run door
 */
    public ChatInjectView inject(long sessionId, long userId, String content,
                                 boolean systemManager) {
        ChatInjectLanding landing = coreClient.injectUser(sessionId, userId, content);
        if (landing != null) {
            return new ChatInjectView(true, landing.messageId(), landing.seq());
        }
        return new ChatInjectView(false,
                coreClient.startRun(sessionId, userId, content, null, null, systemManager, null),
                0L);
    }

    /** Push a batch in seq order and return the advanced cursor. */
    private static long forward(List<ChatFrameView> frames, StreamFrameWriter writer, long cursor) {
        long advanced = cursor;
        for (ChatFrameView frame : frames) {
            writer.write(frame.eventType(), frame.seq(), frame.payload(), frame.metaJson());
            advanced = Math.max(advanced, frame.seq());
        }
        return advanced;
    }

    /** Index of the first terminal frame in a batch, or -1 when none closes the run. */
    private static int terminalIndexOf(List<ChatFrameView> frames) {
        for (int i = 0; i < frames.size(); i++) {
            String type = frames.get(i).eventType();
            if (FRAME_DONE.equals(type) || FRAME_ERROR.equals(type)) {
                return i;
            }
        }
        return -1;
    }

    /** Pump-side degradation: one error frame plus the seal — the browser never hangs. */
    private void injectTerminalError(StreamFrameWriter writer, long seq, String reason) {
        writer.write(FRAME_ERROR, seq, reason, null);
        writer.complete();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
