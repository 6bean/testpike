package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Ledger-backed stream sink contract : every moment
 * the streaming ReAct loop pushes to the sink must land as exactly one ledger
 * frame through the core internal door — delta/reason as their own wire types,
 * tool brackets as trace frames whose meta names the phase and the tool.
 * <p>L2 unit posture identical to {@link ReActRunnerStreamingTest}: the core
 * gateway is isolated here (the real HTTP wiring of the door is pinned by
 * CoreGatewayFrameLedgerTest, the ledger semantics by the core-side tests).
 * A ledger write failure is NOT swallowed — the turn must end honestly.
 */
class FrameLedgerStreamSinkTest {

    @Test
    void userInjectJournalsVerbatimTextWithStepNumberMeta() {
        CoreGateway gateway = mock(CoreGateway.class);
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(gateway, 9L, 42L);

        sink.onUserInject("把重点放在登录", 2);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("trace"),
                payload.capture(), meta.capture());
        assertEquals("把重点放在登录", payload.getValue(),
                "the consumption marker carries the injected text verbatim");
        assertTrue(meta.getValue().contains("\"phase\":\"user_inject\""), meta.getValue());
        assertTrue(meta.getValue().contains("\"stepNo\":2"), meta.getValue());
    }

    @Test
    void steeringTextsComeFromTheInjectedReaderOnlyWhenOneIsWired() {
        CoreGateway gateway = mock(CoreGateway.class);
        FrameLedgerStreamSink withReader = new FrameLedgerStreamSink(gateway, 9L, 42L,
                () -> java.util.List.of("补充一", "补充二"));
        FrameLedgerStreamSink withoutReader = new FrameLedgerStreamSink(gateway, 9L, 42L);

        assertEquals(java.util.List.of("补充一", "补充二"), withReader.takeSteeringTexts());
        assertEquals(java.util.List.of(), withoutReader.takeSteeringTexts(),
                "generator pipelines and un-steered ledger sinks poll nothing and drift nothing");
    }

    @Test
    void deltaAndReasonLandAsTheirOwnFrameTypes() {
        CoreGateway gateway = mock(CoreGateway.class);
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(gateway, 9L, 42L);

        sink.onReason("先看失败清单");
        sink.onDelta("分析完成");

        verify(gateway).appendChatFrame(9L, 42L, "reason", "先看失败清单", null);
        verify(gateway).appendChatFrame(9L, 42L, "delta", "分析完成", null);
    }

    @Test
    void toolStartLandsAsTraceFrameWithPhaseToolStart() {
        CoreGateway gateway = mock(CoreGateway.class);
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(gateway, 9L, 42L);

        sink.onToolStart("kb_search");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("trace"), payload.capture(), meta.capture());
        assertTrue(payload.getValue().contains("kb_search"), payload.getValue());
        assertTrue(meta.getValue().contains("\"phase\":\"tool_start\""), meta.getValue());
        assertTrue(meta.getValue().contains("\"tool\":\"kb_search\""), meta.getValue());
    }

    @Test
    void toolDoneReportsSuccessAndRefusalBoth() {
        CoreGateway gateway = mock(CoreGateway.class);
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(gateway, 9L, 42L);

        sink.onToolDone("kb_search", true, "{\"data\":1}");
        sink.onToolDone("x-write", false, "写语义无人确证（已拒绝执行）");

        ArgumentCaptor<String> okMeta = ArgumentCaptor.forClass(String.class);
        verify(gateway, org.mockito.Mockito.times(2))
                .appendChatFrame(eq(9L), eq(42L), eq("trace"), any(), okMeta.capture());
        assertTrue(okMeta.getAllValues().stream()
                .anyMatch(m -> m.contains("\"phase\":\"tool_done\"") && m.contains("\"success\":true")),
                okMeta.getAllValues().toString());

        ArgumentCaptor<String> refusedPayload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> refusedMeta = ArgumentCaptor.forClass(String.class);
        verify(gateway, org.mockito.Mockito.atLeastOnce())
                .appendChatFrame(eq(9L), eq(42L), eq("trace"), refusedPayload.capture(), refusedMeta.capture());
        assertTrue(refusedMeta.getAllValues().stream()
                .anyMatch(m -> m.contains("\"tool\":\"x-write\"") && m.contains("\"success\":false")),
                refusedMeta.getAllValues().toString());
        assertTrue(refusedPayload.getAllValues().stream()
                .anyMatch(p -> p.contains("x-write")), refusedPayload.getAllValues().toString());
    }

    @Test
    void ledgerWriteFailurePropagatesWithoutSilentLoss() {
        CoreGateway gateway = mock(CoreGateway.class);
        org.mockito.Mockito.when(gateway.appendChatFrame(anyLong(), anyLong(), eq("delta"), any(), any()))
                .thenThrow(new ServiceException("ledger door unreachable",
                        com.sixbean.testpike.common.exception.BizErrorCode.VALIDATION_ERROR));
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(gateway, 9L, 42L);

        assertThrows(ServiceException.class, () -> sink.onDelta("半句都不能假装丢"));
    }

    // ---- stage 3a: the flow-guide tool's structured answer rides the ----
    // ---- existing trace frame meta (no new frame kind in the frozen nine). ----

    @Test
    void flowGuideSuccessEmbedsItsAnswerIntoTraceMetaAsGuide() {
        CoreGateway gateway = mock(CoreGateway.class);
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(gateway, 9L, 42L);
        String answer = "{\"caseId\":5,\"currentStatus\":2,\"currentStatusLabel\":\"待评审\","
                + "\"states\":[{\"code\":2,\"actions\":[{\"event\":\"pass\","
                + "\"page\":\"/review\"}]}]}";

        sink.onToolDone("case.flow-guide", true, answer);

        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("trace"), any(), meta.capture());
        assertTrue(meta.getValue().contains("\"guide\":"), meta.getValue());
        assertTrue(meta.getValue().contains("\"page\":\"/review\""),
                "the actionable transition list must be readable by the client verbatim: "
                        + meta.getValue());
    }

    @Test
    void otherToolsKeepTheirOutputOutOfTheTraceMeta() {
        CoreGateway gateway = mock(CoreGateway.class);
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(gateway, 9L, 42L);

        sink.onToolDone("kb_search", true, "{\"data\":1}");

        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("trace"), any(), meta.capture());
        assertFalse(meta.getValue().contains("\"guide\":"),
                "only the flow-guide answer is structured client-facing data: " + meta.getValue());
    }

    @Test
    void refusedFlowGuideCarriesNoGuideField() {
        CoreGateway gateway = mock(CoreGateway.class);
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(gateway, 9L, 42L);

        sink.onToolDone("case.flow-guide", false, "工具「case.flow-guide」执行失败：目标不可达");

        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("trace"), any(), meta.capture());
        assertFalse(meta.getValue().contains("\"guide\":"), meta.getValue());
    }

    @Test
    void malformedFlowGuideOutputIsNotEmbedded() {
        CoreGateway gateway = mock(CoreGateway.class);
        FrameLedgerStreamSink sink = new FrameLedgerStreamSink(gateway, 9L, 42L);

        sink.onToolDone("case.flow-guide", true, "not-a-json-object");

        ArgumentCaptor<String> meta = ArgumentCaptor.forClass(String.class);
        verify(gateway).appendChatFrame(eq(9L), eq(42L), eq("trace"), any(), meta.capture());
        assertFalse(meta.getValue().contains("\"guide\":"),
                "an unparseable body must not be smuggled into a jsonb meta: " + meta.getValue());
    }
}
