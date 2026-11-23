package com.sixbean.testpike.admin.aichat.app.service;

import com.sixbean.testpike.admin.aichat.api.dto.ChatFrameView;
import com.sixbean.testpike.admin.aichat.api.dto.ChatInjectLanding;
import com.sixbean.testpike.admin.aichat.api.dto.ChatInjectView;
import com.sixbean.testpike.admin.aichat.app.component.StreamFrameRecorder;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import com.sixbean.testpike.common.aichat.ChatImageAttachment;
import com.sixbean.testpike.common.aichat.ChatObjectRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 spectra of the admin SSE pump :
 * a fresh run forwards the whole frame sequence and closes on done; a resume
 * replays only frames after the cursor without starting a new run; silence
 * emits heartbeat comment frames; the first-frame budget and the stream-idle
 * timeout each end the connection with an honest error frame.
 * <p>Infrastructure isolation only: the core door is faked in memory (L2 rule);
 * the real admin→core→ai-service chain is proven by the L3/L4 verification pass.
 */
class ChatStreamBridgeTest {

    private static ChatFrameView frame(long seq, String type, String payload) {
        return new ChatFrameView(0L, 7L, 42L, seq, type, payload, null);
    }

    /** In-memory core door: scripted arrivals per poll, cursor-filtered ledger. */
    private static final class FakeClient implements CoreAiChatClient {
        final List<List<ChatFrameView>> arrivals = new ArrayList<>();
        /** Frames of the run this pump opened, delivered regardless of sealing. */
        final List<ChatFrameView> sealedRunFrames = new ArrayList<>();
        final Map<String, Integer> configs = new HashMap<>();
        int replayCalls;
        int startRunCalls;
        int sealedProbes;
        /** Deliver the sealed block only from this many probes on (race rehearsal). */
        int sealAfterProbes;

        /** Last prompt seen by the run door (steering degrade assertion). */
        String lastPrompt;

        /** Last refs list seen by the run door (knife 4b pass-through assertion). */
        List<ChatObjectRef> lastRefs;

        /** Last attachment list seen by the run door . */
        List<ChatImageAttachment> lastAttachments;
        /** Last vertical verdict seen by the run door . */
        Boolean lastSystemManager;
        /** Last page anchor seen by the run door . */
        String lastQuestionContext;

        /** Landing handed back by the steering door; null = no turn is running. */
        ChatInjectLanding landing;
        int injectCalls;
        long injectSessionId;
        long injectUserId;
        String injectContent;

        @Override
        public long startRun(long sessionId, long userId, String prompt,
                             List<ChatObjectRef> refObjects,
                             List<ChatImageAttachment> attachments, boolean systemManager,
                             String questionContext) {
            startRunCalls++;
            lastPrompt = prompt;
            lastRefs = refObjects;
            lastAttachments = attachments;
            lastSystemManager = systemManager;
            lastQuestionContext = questionContext;
            return 9L;
        }

        @Override
        public ChatInjectLanding injectUser(long sessionId, long userId, String content) {
            injectCalls++;
            injectSessionId = sessionId;
            injectUserId = userId;
            injectContent = content;
            return landing;
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatSessionView createSession(
                long projectId, long userId, String title, int source, String contextJson) {
            throw new UnsupportedOperationException("session opening is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatSessionPageMirrorView listSessions(
                long userId, java.util.List<Long> projectIds, Integer status, Integer source,
                String keyword, int page, int pageSize) {
            throw new UnsupportedOperationException("history listing is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatSessionView renameSession(
                long sessionId, long userId, String title) {
            throw new UnsupportedOperationException("rename is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatSessionUsagePageMirrorView usageAttribution(
                String month, Long projectId, int page, int pageSize) {
            throw new UnsupportedOperationException("cost attribution is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.EvolutionSignalsMirrorView evolutionSignals(
                String month, Long projectId) {
            throw new UnsupportedOperationException("evolution signals are not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.EvolutionTrendMirrorView evolutionTrend(
                String month, Long projectId) {
            throw new UnsupportedOperationException("evolution trend reports are not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.EvolutionProposalsMirrorView generateEvolutionProposals(
                String month, Long projectId, long operatorId) {
            throw new UnsupportedOperationException("proposal runs are not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.EvolutionRollbackMirrorView
                rollbackEvolutionProposal(long draftId, long operatorId) {
            throw new UnsupportedOperationException("proposal rollback is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatSessionView archiveSession(
                long sessionId, long userId) {
            throw new UnsupportedOperationException("archive is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatExportMirrorView exportSession(
                long sessionId, long userId) {
            throw new UnsupportedOperationException("export is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatSedimentMirrorView sedimentSession(
                long sessionId, long userId) {
            throw new UnsupportedOperationException("sediment is not exercised by the pump spectra");
        }

        @Override
        public long undoSession(long sessionId, long userId) {
            throw new UnsupportedOperationException("undo is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatContextWaterlineMirrorView contextWaterline(
                long sessionId, long userId) {
            throw new UnsupportedOperationException("waterline reads are not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView createShare(
                long sessionId, long userId, int ttlHours) {
            throw new UnsupportedOperationException("share is not exercised by the pump spectra");
        }

        @Override
        public java.util.List<com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView> listShares(
                long sessionId, long userId) {
            throw new UnsupportedOperationException("share list is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatShareMirrorView revokeShare(
                long shareId, long userId) {
            throw new UnsupportedOperationException("revoke is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatMessageView abort(long messageId) {
            throw new UnsupportedOperationException("abort is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatMessageView activeMessage(
                long sessionId, long userId) {
            throw new UnsupportedOperationException("active turn door is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.ChatMessageView recordFeedback(
                long messageId, long userId, int feedback, String reason) {
            throw new UnsupportedOperationException("feedback is not exercised by the pump spectra");
        }

        @Override
        public List<com.sixbean.testpike.admin.aichat.api.dto.ChatMessageView> listHistory(
                long sessionId, long userId) {
            throw new UnsupportedOperationException("history reload is not exercised by the pump spectra");
        }

        @Override
        public List<ChatFrameView> replay(long sessionId, long userId, long cursor) {
            List<ChatFrameView> batch =
                    arrivals.isEmpty() ? List.of() : arrivals.get(Math.min(replayCalls, arrivals.size() - 1));
            replayCalls++;
            return batch.stream().filter(f -> f.seq() > cursor).toList();
        }

        @Override
        public List<ChatFrameView> replayRun(long userId, long messageId, long cursor) {
            sealedProbes++;
            if (sealedProbes <= sealAfterProbes) {
                return List.of();
            }
            return sealedRunFrames.stream().filter(f -> f.seq() > cursor).toList();
        }

        @Override
        public int readConfigInt(String key, int defaultValue) {
            return configs.getOrDefault(key, defaultValue);
        }

        @Override
        public String readConfigString(String key, String defaultValue) {
            return configs.containsKey(key) ? String.valueOf(configs.get(key)) : defaultValue;
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.DraftTicketView confirmDraft(
                long draftId, long userId) {
            throw new UnsupportedOperationException("draft confirmation is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.DraftApplyReceiptView applyDraft(
                long draftId, long userId, String confirmToken, String selectedAction) {
            throw new UnsupportedOperationException("draft applying is not exercised by the pump spectra");
        }

        @Override
        public int updateCheckedSubset(long draftId, long userId, java.util.List<String> names) {
            throw new UnsupportedOperationException("subset re-ticking is not exercised by the pump spectra");
        }

        @Override
        public int draftPendingCount(long projectId, String objectType, long targetId) {
            throw new UnsupportedOperationException("pending counting is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.DraftTicketView confirmGroupDrafts(
                long messageId, long userId) {
            throw new UnsupportedOperationException("group confirmation is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.DraftGroupApplyReceiptView applyGroupDrafts(
                long messageId, long userId, String confirmToken, java.util.List<Long> draftIds) {
            throw new UnsupportedOperationException("group applying is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.DraftTicketView confirmGroupDraftsByScope(
                long userId, String groupScope) {
            throw new UnsupportedOperationException(
                    "scope-shaped group confirmation is not exercised by the pump spectra");
        }

        @Override
        public com.sixbean.testpike.admin.aichat.api.dto.DraftGroupApplyReceiptView applyGroupDraftsByScope(
                long userId, String groupScope, String confirmToken, java.util.List<Long> draftIds) {
            throw new UnsupportedOperationException(
                    "scope-shaped group applying is not exercised by the pump spectra");
        }

        @Override
        public java.util.List<com.sixbean.testpike.admin.aichat.api.dto.PendingGroupCardView> groupPendingCards(
                long userId, String groupScope) {
            throw new UnsupportedOperationException(
                    "scope-shaped group card reading is not exercised by the pump spectra");
        }
    }

    private ChatStreamBridge bridge(FakeClient client) {
        return new ChatStreamBridge(client);
    }

    /**
 * S4 identity channel : the verdict computed on the login
 * session face must ride the run door untouched — the bridge relays, it
 * never re-derives roles and never swallows the flag.
 */
    @Test
    void systemManagerVerdictRidesTheRunDoorUntouched() {
        FakeClient client = new FakeClient();
        client.arrivals.add(List.of(frame(1, "done", "{\"usage\":{\"tokens\":1}}")));
        StreamFrameRecorder writer = new StreamFrameRecorder();

        bridge(client).stream(7L, 3L, "who owns these defects", null, null, null, writer, true, null);

        assertEquals(1, client.startRunCalls);
        assertEquals(Boolean.TRUE, client.lastSystemManager,
                "a manager run must reach core's run door still marked manager");
    }

    /**
 * the page anchor of a fresh question must ride the run
 * door untouched — admin mirrors the wire shape only, core's whitelist
 * door stays the single validation point (R51). A reconnect carries no
 * anchor because it opens no run.
 */
    @Test
    void freshRunCarriesTheQuestionPageAnchorToTheCoreDoor() {
        FakeClient client = new FakeClient();
        client.arrivals.add(List.of(frame(1, "done", "{\"usage\":{\"tokens\":1}}")));
        StreamFrameRecorder writer = new StreamFrameRecorder();

        bridge(client).stream(7L, 3L, "what is on this page", null, null, null, writer, false,
                "{\"route\":\"/executions\",\"projectId\":7}");

        assertEquals(1, client.startRunCalls);
        assertEquals("{\"route\":\"/executions\",\"projectId\":7}", client.lastQuestionContext,
                "the anchor built at send time must reach core's run door untouched");
    }

    /**
     * Fresh-question spectra of the steering door: when core reports a landing
     * the supplement is journaled on the running turn and the bridge never
     * opens a second run — that is what keeps one answer in one block.
     */
    @Test
    void steeringTextLandsOnTheRunningTurnWithoutOpeningANewRun() {
        FakeClient client = new FakeClient();
        client.landing = new ChatInjectLanding(9L, 21L);

        ChatInjectView view = bridge(client).inject(7L, 3L, "use the staging env", true);

        assertEquals(1, client.injectCalls);
        assertEquals(7L, client.injectSessionId);
        assertEquals(3L, client.injectUserId, "identity must ride from the login context");
        assertEquals("use the staging env", client.injectContent,
                "the supplement reaches the core door untouched");
        assertEquals(0, client.startRunCalls, "a landed injection must never open a second run");
        assertTrue(view.injectedIntoRunningTurn());
        assertEquals(9L, view.messageId());
        assertEquals(21L, view.seq(), "the browser cursor follows the ledger seq of the injection");
    }

    /**
     * Honest absence: with no turn running the same text goes through the
     * regular message channel instead of being dropped, and the answer view
     * tells the caller which of the two happened.
     */
    @Test
    void steeringWithoutARunningTurnDegradesIntoARegularMessage() {
        FakeClient client = new FakeClient();

        ChatInjectView view = bridge(client).inject(7L, 3L, "also check the login page", false);

        assertEquals(1, client.startRunCalls,
                "a supplement with no running turn must be submitted as a regular message");
        assertEquals("also check the login page", client.lastPrompt);
        assertEquals(Boolean.FALSE, client.lastSystemManager,
                "the vertical verdict must reach the degraded run untouched");
        assertFalse(view.injectedIntoRunningTurn());
        assertEquals(9L, view.messageId(), "the degraded view names the turn that carries the text");
    }

    @Test
    void freshRunForwardsEveryFrameAndClosesOnDone() {
        FakeClient client = new FakeClient();
        client.arrivals.add(List.of(frame(1, "state", "{\"stage\":\"received\"}"),
                frame(2, "trace", "{\"phase\":\"tool_start\"}")));
        client.arrivals.add(List.of(frame(3, "delta", "hello")));
        client.arrivals.add(List.of(frame(4, "done", "{\"usage\":{\"tokens\":7}}")));
        StreamFrameRecorder writer = new StreamFrameRecorder();

        bridge(client).stream(7L, 3L, "why is the batch red", null, null, null, writer, false, null);

        assertEquals(1, client.startRunCalls, "a fresh prompt must open exactly one run");
        assertEquals(List.of("state", "trace", "delta", "done"), writer.frameTypes);
        assertEquals(List.of(1L, 2L, 3L, 4L), writer.seqs, "SSE id: must carry the ledger seq");
        assertTrue(writer.completed, "the done frame must close the connection");
    }

    /** Knife 4b pass-through: chips ride the fresh question to the core door intact. */
    @Test
    void freshRunCarriesTheReferenceChipsIntoTheCoreDoor() {
        FakeClient client = new FakeClient();
        client.arrivals.add(List.of(frame(1, "done", "{}")));
        StreamFrameRecorder writer = new StreamFrameRecorder();

        bridge(client).stream(7L, 3L, "compare these",
                List.of(new ChatObjectRef("case", 7L, "登录用例"),
                        new ChatObjectRef("batch", 8L, "夜间回归")),
                null, null, writer, false, null);

        assertEquals(2, client.lastRefs.size());
        assertEquals("case", client.lastRefs.get(0).kind());
        assertEquals(8L, client.lastRefs.get(1).id());
    }

    /** P4 pass-through: images ride the fresh question to the core door. */
    @Test
    void freshRunCarriesImageAttachmentsIntoTheCoreDoor() {
        FakeClient client = new FakeClient();
        client.arrivals.add(List.of(frame(1, "done", "{}")));
        StreamFrameRecorder writer = new StreamFrameRecorder();

        bridge(client).stream(7L, 3L, "看看这张截图", null,
                List.of(new ChatImageAttachment("tp/7/chat-image/202610/a1b2c3d4-shot.png", "shot.png")),
                null, writer, false, null);

        assertEquals(1, client.lastAttachments.size());
        assertEquals("tp/7/chat-image/202610/a1b2c3d4-shot.png",
                client.lastAttachments.get(0).objectKey());
    }

    /** A resume re-attaches to a run that already holds its chips — none are resent. */
    @Test
    void resumeSendsNoReferenceChips() {
        FakeClient client = new FakeClient();
        client.arrivals.add(List.of(frame(4, "done", "{}")));
        StreamFrameRecorder writer = new StreamFrameRecorder();

        bridge(client).stream(7L, 3L, null,
                List.of(new ChatObjectRef("case", 7L, "旧引用")), null, 3L, writer, false, null);

        assertEquals(0, client.startRunCalls);
        assertNull(client.lastRefs, "a reconnect must not re-open a run with chips");
    }

    @Test
    void resumeReplaysOnlyFramesAfterTheCursorWithoutStartingARun() {
        FakeClient client = new FakeClient();
        client.arrivals.add(List.of(frame(3, "delta", "tail"),
                frame(4, "done", "{}")));
        StreamFrameRecorder writer = new StreamFrameRecorder();

        bridge(client).stream(7L, 3L, null, null, null, 2L, writer, false, null);

        assertEquals(0, client.startRunCalls, "a reconnect must never open a second run");
        assertEquals(List.of("delta", "done"), writer.frameTypes);
        assertEquals(List.of(3L, 4L), writer.seqs);
        assertTrue(writer.completed);
    }

    @Test
    void silenceEmitsHeartbeatCommentFrames() {
        FakeClient client = new FakeClient();
        client.configs.put("ai.chat.heartbeat-seconds", 1);
        client.configs.put("ai.chat.first-frame-budget-seconds", 100);
        client.configs.put("ai.chat.stream-idle-timeout-seconds", 100);
        // ~2s of empty polls (each poll sleeps 20ms in the pump), then done lands.
        for (int i = 0; i < 80; i++) {
            client.arrivals.add(List.of());
        }
        client.arrivals.add(List.of(frame(1, "done", "{}")));
        StreamFrameRecorder writer = new StreamFrameRecorder();

        bridge(client).stream(7L, 3L, "hi", null, null, null, writer, false, null);

        assertTrue(writer.heartbeats >= 1, "silence past the heartbeat interval must keep the wire warm");
        assertEquals(List.of("done"), writer.frameTypes);
        assertTrue(writer.completed);
    }

    @Test
    void firstFrameBudgetOverrunEndsTheStreamWithAnErrorFrame() {
        FakeClient client = new FakeClient();
        client.configs.put("ai.chat.first-frame-budget-seconds", 1);
        client.configs.put("ai.chat.stream-idle-timeout-seconds", 100);
        for (int i = 0; i < 40; i++) {
            client.arrivals.add(List.of());
        }
        StreamFrameRecorder writer = new StreamFrameRecorder();

        bridge(client).stream(7L, 3L, "hi", null, null, null, writer, false, null);

        assertEquals(List.of("error"), writer.frameTypes, "budget overrun must inject exactly one honest error frame");
        assertTrue(writer.payloads.get(0) != null && !writer.payloads.get(0).isBlank(),
                "the error payload must be a human-readable reason");
        assertTrue(writer.completed);
    }

    @Test
    void sealedRunFramesAreDeliveredInsteadOfAFakeBudgetTimeout() {
        // Live L3 defect (spectra A/B): the worker refused/answered in milliseconds and
        // sealed the block before the pump's first poll. The session-wide replay stays
        // empty forever, so the old pump served a budget-timeout error that the ledger
        // disproved. The run this request opened must be delivered whole, terminal
        // frame included, and must close the stream on its own merits.
        FakeClient client = new FakeClient();
        client.configs.put("ai.chat.first-frame-budget-seconds", 1);
        client.configs.put("ai.chat.stream-idle-timeout-seconds", 100);
        for (int i = 0; i < 40; i++) {
            client.arrivals.add(List.of());
        }
        client.sealedRunFrames.addAll(List.of(
                frame(5, "state", "{\"stage\":\"received\"}"),
                frame(6, "error", "在线 AI 对话当前未开启")));
        StreamFrameRecorder writer = new StreamFrameRecorder();

        bridge(client).stream(7L, 3L, "hi", null, null, null, writer, false, null);

        assertEquals(List.of("state", "error"), writer.frameTypes,
                "the sealed run must reach the client, never a pump-invented timeout");
        assertEquals(List.of(5L, 6L), writer.seqs, "ledger seqs survive the sealed-run delivery");
        assertTrue(writer.completed);
    }

    @Test
    void sealedBlockAppearingAfterTheFirstProbeIsStillDelivered() {
        // Race shape seen on the live chain: the pump probes right after opening the
        // run (ledger still empty, worker still behind MQ), the worker seals a few
        // hundred ms later. The throttled probe must keep checking, so the real
        // refusal frame reaches the client long before the first-frame budget fires.
        FakeClient client = new FakeClient();
        client.configs.put("ai.chat.first-frame-budget-seconds", 5);
        client.configs.put("ai.chat.stream-idle-timeout-seconds", 100);
        client.sealAfterProbes = 2;
        client.sealedRunFrames.addAll(List.of(
                frame(3, "error", "在线 AI 对话当前未开启")));
        StreamFrameRecorder writer = new StreamFrameRecorder();

        long started = System.currentTimeMillis();
        bridge(client).stream(7L, 3L, "hi", null, null, null, writer, false, null);
        long elapsed = System.currentTimeMillis() - started;

        assertEquals(List.of("error"), writer.frameTypes,
                "the ledger's refusal frame is delivered, never the pump-invented timeout");
        assertTrue(elapsed < 4_000,
                "delivery within the budget window, not after it: elapsed=" + elapsed + "ms");
        assertTrue(writer.payloads.get(0).contains("未开启"), writer.payloads.get(0));
    }

    @Test
    void heartbeatsNeverMaskTheIdleTimeout() {
        // Real-chain defect: the transport keep-warm must not reset the "no new
        // content" clock. With heartbeat shorter than idle, resetting on every beat
        // made the idle timeout unreachable — the resumed stream hung for 30 minutes
        // on the L3 door while the ledger said the turn was long finished.
        FakeClient client = new FakeClient();
        client.configs.put("ai.chat.heartbeat-seconds", 1);
        client.configs.put("ai.chat.stream-idle-timeout-seconds", 2);
        client.configs.put("ai.chat.first-frame-budget-seconds", 100);
        StreamFrameRecorder writer = new StreamFrameRecorder();

        long started = System.currentTimeMillis();
        bridge(client).stream(7L, 3L, null, null, null, 5L, writer, false, null);
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(writer.heartbeats >= 1, "keep-warm beats still land during the wait");
        assertEquals(List.of("error"), writer.frameTypes, "idle must end the stream on its own clock");
        assertTrue(elapsed < 6_000, "idle fires at its own second count: elapsed=" + elapsed + "ms");
        assertTrue(writer.completed);
    }

    @Test
    void streamIdleTimeoutEndsTheStreamWithAnErrorFrame() {
        FakeClient client = new FakeClient();
        client.configs.put("ai.chat.first-frame-budget-seconds", 100);
        client.configs.put("ai.chat.stream-idle-timeout-seconds", 1);
        client.arrivals.add(List.of(frame(1, "state", "{}")));
        for (int i = 0; i < 40; i++) {
            client.arrivals.add(List.of());
        }
        StreamFrameRecorder writer = new StreamFrameRecorder();

        bridge(client).stream(7L, 3L, "hi", null, null, null, writer, false, null);

        assertEquals(List.of("state", "error"), writer.frameTypes);
        assertTrue(writer.completed);
    }
}
