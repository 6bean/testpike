package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractLedgerStore;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractDeadline;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D2 recovery channel (same-generation resume round, option C of the defect
 * ledger): a reconcile loop whose task window is spent mid-run used to bleed
 * out on the first spent clamp and exit with the ledger still pending — the
 * remaining rounds never ran again (live 9071: zero tp_ai_call_log rows after
 * the budget death, users only had the full-regeneration retry). The loop now
 * may re-anchor the window ONCE (hot key ai.doc.extract-reconcile-resume-
 * seconds, 300~3600, 0 = off keeps the legacy face byte-identical) and re-run
 * the round it bled on; the round cap is unchanged — a resume never buys extra
 * round quota.
 * <p>Fake clock (taskClock seam + ExtractDeadline's LongSupplier): the window
 * is spent deterministically with zero sleeps. L2 posture: gateway/router/
 * ledger are infrastructure mocks (same face as DocExtractReconcileTest);
 * real-chain behaviour lives at L4.
 */
class DocExtractReconcileResumeTest {

    private static final long DOC_ID = 9601L;
    private static final long PROJECT_ID = 9603L;
    private static final String TOKEN = "gen-token-reconcile-resume-001";
    private static final String KEY_RESUME_SECONDS = "ai.doc.extract-reconcile-resume-seconds";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final DocExtractLedgerStore ledgerStore = mock(DocExtractLedgerStore.class);
    private final DocExtractServiceImpl service = DocExtractTestSupport.wiredService(
            coreGateway, llmChain, llmRouter, ledgerStore);

    /** Fake epoch millis; the run arms its 3600s window at 0. */
    private final AtomicLong now = new AtomicLong();
    private final AtomicInteger chatCalls = new AtomicInteger();

    private static final String LOGIN_TEXT = "用户连续输错密码五次后账号锁定十分钟";
    private static final String EXPORT_TEXT = "报表导出支持按月归档并保留最近二十四个月";
    private static final String RESP_LOGIN_ONLY =
            "{\"requirements\":[{\"title\":\"登录失败锁定\",\"acceptanceCriteria\":\"五次锁定\","
                    + "\"chapter\":\"安全\",\"confidence\":0.9,\"evidence\":\"" + LOGIN_TEXT + "\"}]}";
    private static final String RESP_COVER_EXPORT =
            "{\"requirements\":[{\"title\":\"报表按月归档\",\"acceptanceCriteria\":\"保留24个月\","
                    + "\"chapter\":\"安全\",\"confidence\":0.9,\"evidence\":\"" + EXPORT_TEXT + "\"}]}";

    @BeforeEach
    void armFakeClock() {
        now.set(0L);
        service.setTaskClock(now::get);
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG, 2000);
    }

    private static Map<String, Object> chunk(long id, int seq, String content) {
        return Map.of("id", id, "seq", seq, "chapter", "安全", "content", content);
    }

    /**
     * Live shape: first pass cites only the login chunk (export chunk stays a
     * gap); the reconcile round-1 proposal call is the run's last legitimate
     * transport, so entering that call the fake clock crosses the 3600s window
     * and the budget clamp fails the attempt with AI_TIMEOUT — no sleeps.
     */
    private void stubRunSpendingWindowOnReconcileCall(String resumeKeyValue) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunk(9101L, 1, LOGIN_TEXT), chunk(9102L, 2, EXPORT_TEXT)));
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY)).thenReturn("3600");
        when(coreGateway.getSysConfig(KEY_RESUME_SECONDS)).thenReturn(resumeKeyValue);
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any())).thenAnswer(inv -> {
            int call = chatCalls.incrementAndGet();
            if (call == 2) {
                now.set(3_600_001L); // the window died before this attempt
            }
            UnaryOperator<Duration> clamp = inv.getArgument(4);
            clamp.apply(Duration.ofMinutes(5)); // real chain clamps every attempt
            return new LlmChatResult(call == 1 ? RESP_LOGIN_ONLY : RESP_COVER_EXPORT,
                    "openai", "routed-model", 100, 50, 20L);
        });
    }

    private String landedStepsJson() {
        ArgumentCaptor<String> steps = ArgumentCaptor.forClass(String.class);
        verify(ledgerStore).upsertIfChanged(eq(DOC_ID), eq(TOKEN), steps.capture());
        return steps.getValue();
    }

    private List<String> landedStageCodes() {
        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        verify(ledgerStore, org.mockito.Mockito.atLeastOnce()).recordStage(
                eq(DOC_ID), eq(TOKEN), codes.capture(), any());
        return codes.getAllValues();
    }

    private List<String> landedStageLabels() {
        ArgumentCaptor<String> labels = ArgumentCaptor.forClass(String.class);
        verify(ledgerStore, org.mockito.Mockito.atLeastOnce()).recordStage(
                eq(DOC_ID), eq(TOKEN), any(), labels.capture());
        return labels.getAllValues();
    }

    /** Recovery face: the spent bleed-out resumes once and the remaining round
     *  actually runs — the gap chunk gets its proposal call and the ledger
     *  converges instead of parking pending forever. */
    @Test
    void spentWindowWithPendingLedger_resumesOnceAndRemainingRoundConverges() {
        stubRunSpendingWindowOnReconcileCall("1800");

        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));

        verify(llmChain, times(3)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        assertFalse(landedStepsJson().contains("\"state\":\"pending\""),
                "after the resume round the coverage ledger must converge: " + landedStepsJson());
        assertTrue(landedStageCodes().contains("reconcile-resume"),
                "the resume must land one visible stage row: " + landedStageCodes());
        assertTrue(landedStageLabels().stream()
                        .anyMatch(l -> l.startsWith("完整性复核已延期") && l.contains("1800")),
                "stage label names the recovery with the resume window: " + landedStageLabels());
        org.junit.jupiter.api.Assertions.assertEquals(2,
                DocExtractTestSupport.capturedWrite(coreGateway, DOC_ID, TOKEN).size(),
                "both items land on the write-back once the resume round covers the gap");
    }

    /** Key off (0): byte-identical legacy bleed-out — the spent clamp stops the
     *  loop, the ledger stays honestly pending, no resume stage row appears. */
    @Test
    void resumeDisabled_zeroKeepsTheLegacyBleedOutFace() {
        stubRunSpendingWindowOnReconcileCall("0");

        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));

        verify(llmChain, times(2)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        assertTrue(landedStepsJson().contains("pending"),
                "residual gap must stay honest in the ledger: " + landedStepsJson());
        assertFalse(landedStageCodes().contains("reconcile-resume"),
                "a disabled resume must not land any recovery row: " + landedStageCodes());
    }

    /** Cap guard: the resume re-runs the round it bled on but never extends the
     *  round quota — a run whose rounds are already exhausted at the spent
     *  moment must not gain a fresh round from the recovery window. */
    @Test
    void resumeOnLastRound_doesNotBuyExtraRoundQuota() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunk(9101L, 1, LOGIN_TEXT), chunk(9102L, 2, EXPORT_TEXT)));
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY)).thenReturn("3600");
        when(coreGateway.getSysConfig(KEY_RESUME_SECONDS)).thenReturn("1800");
        when(coreGateway.getSysConfig("ai.doc.extract-reconcile-rounds")).thenReturn("1");
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any())).thenAnswer(inv -> {
            int call = chatCalls.incrementAndGet();
            if (call == 2) {
                now.set(3_600_001L);
            }
            UnaryOperator<Duration> clamp = inv.getArgument(4);
            clamp.apply(Duration.ofMinutes(5));
            return new LlmChatResult(call == 1 ? RESP_LOGIN_ONLY : RESP_COVER_EXPORT,
                    "openai", "routed-model", 100, 50, 20L);
        });

        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));

        // rounds cap = 1: the single reconcile round bled on the spent clamp;
        // no second round may open even with the resume window in stock.
        verify(llmChain, times(2)).chat(any(), eq(PROJECT_ID), any(), anyBoolean(), any());
        assertFalse(landedStageCodes().contains("reconcile-resume"),
                "nothing left to resume into must not burn a resume row: " + landedStageCodes());
    }
}
