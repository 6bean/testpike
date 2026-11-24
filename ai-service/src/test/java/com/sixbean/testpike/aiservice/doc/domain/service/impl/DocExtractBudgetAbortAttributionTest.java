package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.aiservice.doc.domain.component.ExtractDeadline;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunkWithId;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;

/**
 * remediation — honest terminal attribution when the task time budget
 * is exhausted mid-run (live run f8918ae4: the 7200s window died at group 18
 * while every sibling call failed at the deadline clamp, yet the document was
 * that never happened, sending operators after the wrong root cause).
 *
 * <p>Pinned behaviours:
 * <ul>
 * <li>a grouped-raw run whose window is spent aborts with the budget
 * format-validation wording is impossible on that path;</li>
 * <li>once the window is spent NOTHING re-burns — the inline retry loop and
 * the deferred re-submission round stop on the spot (a spent window can
 * <li>one transport call per group is the whole budget-exhaustion bill.</li>
 * </ul>
 *
 * <p>Fixture face: two 100k-char chunks group into two raw groups; K8
 * {@code ai.doc.final-bucket-max-tokens=0} pins the monolithic bucket per
 * group so the per-group call arithmetic is exact; the plane transport applies
 * the run's budget clamp like the real chain and sleeps past the 1s window on
 * every raw call, so the first raw attempt already trips the spent window.
 */
class DocExtractBudgetAbortAttributionTest {

    private static final long DOC_ID = 98L;
    private static final long PROJECT_ID = 38L;
    private static final String TOKEN = "gen-token-budget-abort-001";

    private final DocExtractTestFixtures fixtures =
            new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final DocExtractServiceImpl service = fixtures.pipeline();

    /** Raw-plane calls that actually reached the transport, in call order. */
    private final AtomicInteger rawCalls = new AtomicInteger();

    /** Every plane call applies the budget clamp it was handed (the real
     *  chain clamps its per-call timeout the same way); the raw face first
     *  sleeps past the 1s window, so the clamp throws the budget vocabulary. */
    private LlmChatResult reply(ChatCompletionRequest request, UnaryOperator<Duration> clamp) {
        String system = request.messages().get(0).content();
        if (system.contains(DocExtractServiceImpl.SUMMARY_MARKER)) {
            clamp.apply(Duration.ofMinutes(5));
            return new LlmChatResult("转述概览文本", "openai", "routed-model", 10, 5, 1L);
        }
        rawCalls.incrementAndGet();
        try {
            Thread.sleep(1200L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ie);
        }
        clamp.apply(Duration.ofMinutes(5));
        return new LlmChatResult("{\"requirements\":[]}", "openai", "routed-model", 100, 50, 20L);
    }

    private void stubBudgetSpentRun() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunkWithId(21L, 1, "第一章", "第一章锚句" + "字".repeat(100_000)),
                chunkWithId(22L, 2, "第二章", "第二章锚句" + "字".repeat(100_000))));
        // 1s task window (K8 face, positive value honoured verbatim).
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY)).thenReturn("1");
        when(coreGateway.getSysConfig("ai.doc.final-bucket-max-tokens")).thenReturn("0");
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any()))
                .thenAnswer(inv -> reply(inv.getArgument(0), inv.getArgument(4)));
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), any()))
                .thenAnswer(inv -> new LlmChatHandle(reply(inv.getArgument(0), inv.getArgument(4)),
                        endpoint()));
    }

    @Test
    void spentWindowAbortsTerminalWithBudgetVocabularyAndZeroSpinningRetries() {
        stubBudgetSpentRun();

        assertThrows(ServiceException.class, () -> service.extract(fixtures.message()));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).markExtractionFailed(eq(DOC_ID), reason.capture(), eq(TOKEN));
        String written = reason.getValue();
        assertTrue(written.contains("时长上限"),
                "预算耗尽的终文案必须点名任务时长预算, 实际=" + written);
        assertTrue(written.contains("0/2 组"),
                "终文案必须带已完成组数账（本例两组全灭=0/2）, 实际=" + written);
        assertFalse(written.contains("格式校验失败"),
                "全链零格式校验失败实锚时禁折叠成该文案（缺陷A归因错位）, 实际=" + written);
        // Defect B: both groups fail their FIRST baseTask attempt after the
        // sleep; a spent window must stop the inline retry loop and skip the
        // deferred round entirely — pre-fix this burned 8 transport calls
        // (4 attempts × the streaming + one-shot blocking fallback pair, every
        // one an instant clamp death).
        assertEquals(4, rawCalls.get(),
                "预算耗尽后每组只许一次尝试（流式+一次非流式回退=2 次传输），禁内联重试与延后重提空转, 实际="
                        + rawCalls.get());
    }

    // measure-1 propagation face: the group that first finds the run
    // window spent must stop every unstarted sibling at the fan-out —
    // provider entries for the never-started groups stay exactly 0 and the
    // caller returns within seconds instead of riding 3s sibling sleeps.

    /** Transport entries per chapter (one 100k chunk = one raw group). */
    private final java.util.Map<Integer, AtomicInteger> chapterCalls =
            new java.util.concurrent.ConcurrentHashMap<>();

    private int calls(int chapter) {
        AtomicInteger counter = chapterCalls.get(chapter);
        return counter == null ? 0 : counter.get();
    }

    private LlmChatResult replyWithSlowSiblings(ChatCompletionRequest request,
                                                UnaryOperator<Duration> clamp) {
        String joined = request.messages().stream()
                .map(m -> m.content() == null ? "" : m.content())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (joined.contains(DocExtractServiceImpl.SUMMARY_MARKER)) {
            clamp.apply(Duration.ofMinutes(5));
            return new LlmChatResult("转述概览文本", "openai", "routed-model", 10, 5, 1L);
        }
        int chapter = 0;
        for (int ch = 1; ch <= 6; ch++) {
            if (joined.contains("第" + ch + "章锚句")) {
                chapter = ch;
                break;
            }
        }
        chapterCalls.computeIfAbsent(chapter == 0 ? 1 : chapter, k -> new AtomicInteger())
                .incrementAndGet();
        try {
            // Chapter one is the first to trip the spent window (2 calls ≈
            // 2.4s); the other groups hold 3s sleeps so an unpropagated run
            // keeps launching siblings long past the gate trip.
            Thread.sleep(chapter == 1 ? 1200L : 3000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ie);
        }
        clamp.apply(Duration.ofMinutes(5));
        return new LlmChatResult("{\"requirements\":[]}", "openai", "routed-model", 100, 50, 20L);
    }

    private void stubSixGroupSpendingRun() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunkWithId(31L, 1, "第一章", "第1章锚句" + "字".repeat(100_000)),
                chunkWithId(32L, 2, "第二章", "第2章锚句" + "字".repeat(100_000)),
                chunkWithId(33L, 3, "第三章", "第3章锚句" + "字".repeat(100_000)),
                chunkWithId(34L, 4, "第四章", "第4章锚句" + "字".repeat(100_000)),
                chunkWithId(35L, 5, "第五章", "第5章锚句" + "字".repeat(100_000)),
                chunkWithId(36L, 6, "第六章", "第6章锚句" + "字".repeat(100_000))));
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY)).thenReturn("1");
        when(coreGateway.getSysConfig("ai.doc.final-bucket-max-tokens")).thenReturn("0");
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean(), any()))
                .thenAnswer(inv -> replyWithSlowSiblings(inv.getArgument(0), inv.getArgument(4)));
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), any()))
                .thenAnswer(inv -> new LlmChatHandle(
                        replyWithSlowSiblings(inv.getArgument(0), inv.getArgument(4)),
                        endpoint()));
    }

    @Test
    void spentWindowFoundByOneGroupStopsEveryUnstartedSibling() {
        stubSixGroupSpendingRun();

        long start = System.nanoTime();
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> service.extract(fixtures.message()));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;

        String written = thrown.getMessage();
        assertTrue(written.contains("时长上限"),
                "终文案仍是预算词汇（诚实归因零漂移）, 实际=" + written);
        assertTrue(written.contains("0/6 组"),
                "六组全部未落地时完成账必须诚实为 0/6, 实际=" + written);
        assertEquals(2, calls(1),
                "首组闸断前跑满一次尝试的两个传输（流式+回退）, 实际=" + calls(1));
        assertEquals(0, calls(4) + calls(5) + calls(6),
                "闸断传导后未启动兄弟组的供应商调用增量必须恒 0, 实际 4/5/6="
                        + calls(4) + "/" + calls(5) + "/" + calls(6));
        assertTrue(elapsedMillis < 2_900L,
                "在飞兄弟被中断、调用方秒级返回，禁陪跑 3s 睡眠链, 实际(ms)=" + elapsedMillis);
    }
}
