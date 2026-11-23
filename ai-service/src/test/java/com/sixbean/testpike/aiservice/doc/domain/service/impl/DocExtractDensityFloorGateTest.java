package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractLedgerStore;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EF batch (recall-density floor): a dense descriptive block that every
 * citation settles coverage on still leaves the OUTPUT face under the source's
 * real information density — the live 8491/8639 pattern where a 121k-token
 * document came back as a handful of items and both the block ledger and the
 * item-count deficit read "done". The floor turns the run's own item count
 * against the tokens actually shown into a loop entry: when
 * {@code merged.size < ceil(sourceTokens × floor / 1000)} the reconcile loop
 * re-mines the whole shown package with a dimension-focused ask. The hot key
 * ai.doc.recall-density-floor-per-ktoken (factory 6.4, range 0-50) gates it;
 * 0 turns the check off, garbage or out-of-range falls back to the default.
 * <p>Byte-identity red lines the guards below pin: small sources whose own
 * expected item count is already met never fire the floor, and ordinary
 * gap-driven rounds keep their exact pre- prompt (no directive drift).
 * <p>L2 posture: same isolation face as DocExtractReconcileTest — CoreGateway
 * / LlmFallbackChain / router / ledger store are infrastructure mocks;
 * real extraction recall lives at L4 and the golden face.
 */
class DocExtractDensityFloorGateTest {

    private static final long DOC_ID = 7L;
    private static final long PROJECT_ID = 3L;
    private static final String TOKEN = "gen-token-density-001";
    private static final String KEY_FLOOR = "ai.doc.recall-density-floor-per-ktoken";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final DocExtractLedgerStore ledgerStore = mock(DocExtractLedgerStore.class);
    private final DocExtractServiceImpl service = DocExtractTestSupport.wiredService(
            coreGateway, llmChain, llmRouter, ledgerStore);

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachRecallLogs() {
        logAppender = DocExtractTestSupport.attachServiceLogCapture();
    }

    @AfterEach
    void detachRecallLogs() {
        DocExtractTestSupport.detachServiceLogCapture(logAppender);
    }

    private static LlmEndpointConfig endpoint() {
        // 8000-token window keeps the char-face planner well under budget so
        // the fixture rides the flat single-bucket baseline (the 2000 endpoint
        // of DocExtractReconcileTest would push a 3.5k-char source onto the
        // over-budget grouped plane and burn responses on group planes).
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG, 8000);
    }

    private static AiDocExtractMessage message() {
        return new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN);
    }

    private static Map<String, Object> chunk(long id, int seq, String content) {
        return Map.of("id", id, "seq", seq, "chapter", "报表", "content", content);
    }

    /**
 * One descriptive sentence deliberately free of the modal family and
 * of enumeration markers — expectedEntries stays at 1 so a single citing
 * item empties both ledger faces and ONLY the density floor can fire the
 * loop (the isolation this package's red line demands).
 */
    private static final String UNIT = "这张报表按月归档留存二十四个月的历史记录页面采用蓝白色调。";
    /** 120 repeats ≈ 3480 chars → char-face 870 tokens → floor 6.4 expects 6 items. */
    private static final String DENSE = UNIT.repeat(120);
    private static final String EXCERPT = "报表按月归档留存二十四个月";

    private static String itemJson(String title, String evidence) {
        return "{\"title\":\"" + title + "\",\"evidence\":\"" + evidence + "\"}";
    }

    private static String firstRoundResponse() {
        return "{\"requirements\":[" + itemJson("报表归档留存", EXCERPT) + "]}";
    }

    /** Five further verbatim-citing items — merged reaches the floor's 6. */
    private static String miningRoundResponse(int count) {
        String items = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> itemJson("补充条目" + i, EXCERPT))
                .collect(Collectors.joining(","));
        return "{\"requirements\":[" + items + "]}";
    }

    private void stub(List<Map<String, Object>> chunks, String... responses) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
        org.mockito.stubbing.OngoingStubbing<LlmChatResult> chain = when(llmChain.chat(any(),
                eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean(), any()));
        for (String resp : responses) {
            chain = chain.thenReturn(new LlmChatResult(resp, "openai", "routed-model",
                    100, 50, 20L));
        }
    }

    @SuppressWarnings("unchecked")
    private String userPromptOfCall(int callIndex) {
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(callIndex)).chat(captor.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean(), any());
        List<ChatMessage> msgs = captor.getAllValues().get(callIndex - 1).messages();
        return msgs.get(1).content();
    }

    private List<String> capturedStageCodes() {
        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        verify(ledgerStore, atLeastOnce()).recordStage(eq(DOC_ID), eq(TOKEN),
                codes.capture(), any());
        return codes.getAllValues();
    }

    @Test
    void denseSource_underFloorItems_firesMiningRound_withDimensionDirective() {
        stub(List.of(chunk(101L, 1, DENSE)),
                firstRoundResponse(), miningRoundResponse(5));
        service.extract(message());
        // one first-pass call + one density round; floor reached -> loop stops
        verify(llmChain, times(2)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        String secondPrompt = userPromptOfCall(2);
        assertTrue(secondPrompt.contains(UNIT),
                "density round must re-mine the shown package, not an empty gap list");
        assertTrue(secondPrompt.contains("主流程、等价类、反例、边界、状态组合"),
                "density round carries the dimension-focused ask: " + secondPrompt);
        assertTrue(logAppender.list.stream().anyMatch(e ->
                        e.getFormattedMessage().contains("F2 密度下限闸")),
                "the trigger must be named on the log face");
        assertTrue(capturedStageCodes().contains("density-floor"),
                "preview face gets its own stage row");
        assertTrue(DocExtractTestSupport.capturedWrite(coreGateway, DOC_ID, TOKEN).size() >= 6,
                "mining-round items merge before write-back: " + DocExtractTestSupport.capturedWrite(coreGateway, DOC_ID, TOKEN).size());
    }

    @Test
    void floorKeyZero_checkOff_byteIdenticalLegacySingleCall() {
        when(coreGateway.getSysConfig(KEY_FLOOR)).thenReturn("0");
        stub(List.of(chunk(101L, 1, DENSE)), firstRoundResponse(), miningRoundResponse(5));
        service.extract(message());
        verify(llmChain, times(1)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        assertFalse(logAppender.list.stream().anyMatch(e ->
                        e.getFormattedMessage().contains("F2 密度下限闸")),
                "an explicit 0 must keep the floor fully out of the loop");
        assertFalse(capturedStageCodes().contains("density-floor"),
                "an off floor lands no density stage row");
    }

    @Test
    void garbageFloorKey_fallsBackToDefault_stillFires() {
        when(coreGateway.getSysConfig(KEY_FLOOR)).thenReturn("abc");
        stub(List.of(chunk(101L, 1, DENSE)),
                firstRoundResponse(), miningRoundResponse(5));
        service.extract(message());
        verify(llmChain, times(2)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
    }

    @Test
    void outOfRangeFloorKey_fallsBackToDefault_stillFires() {
        when(coreGateway.getSysConfig(KEY_FLOOR)).thenReturn("60");
        stub(List.of(chunk(101L, 1, DENSE)),
                firstRoundResponse(), miningRoundResponse(5));
        service.extract(message());
        verify(llmChain, times(2)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
    }

    /**
     * Known pitfall the recon sealed: every short-block fixture in the
     * existing reconcile tests (~40 chars → expected 1) must never see a new
     * round once its single citing item lands — no false-positive re-burn.
     */
    @Test
    void tinySource_floorAlreadyMet_neverFiresExtraRound() {
        String tiny = "水印不得关闭";
        stub(List.of(chunk(101L, 1, tiny)),
                "{\"requirements\":[" + itemJson("水印", "水印不得关闭") + "]}");
        service.extract(message());
        verify(llmChain, times(1)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        assertFalse(logAppender.list.stream().anyMatch(e ->
                        e.getFormattedMessage().contains("F2 密度下限闸")),
                "a met floor must stay silent");
    }

    /**
     * The floor keeps the loop alive while rounds still bring gains, and the
     * round cap stays the hard bound (the parser rejects an empty mining
     * response upstream, so bounded-gain is the honest zero-gain face).
     */
    @Test
    void sustainedMiningGains_stopsAtRoundCap_neverBurnsPastThree() {
        when(coreGateway.getSysConfig(KEY_FLOOR)).thenReturn("20");
        stub(List.of(chunk(101L, 1, DENSE)), firstRoundResponse(),
                miningRoundResponse(5), miningRoundResponse(5), miningRoundResponse(5));
        service.extract(message());
        // 870 char-tokens x 20/1000 -> floor 18; 1+5+5+5=16 stays under it,
        // so the loop runs exactly to the cap (1 first pass + 3 reconcile)
        verify(llmChain, times(4)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        assertTrue(capturedStageCodes().contains("density-floor"),
                "the floor earns its own preview stage row once");
    }
}
