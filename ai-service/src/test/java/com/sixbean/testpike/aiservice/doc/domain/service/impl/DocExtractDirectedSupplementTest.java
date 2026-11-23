package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractLedgerStore;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * server face of the directed supplement round (the shipped message
 * contract {@code AiDocExtractMessage.chunkIds} promised one round over the
 * named chunks; the live rerun re-read all 94 blocks for 924s and the
 * kept-pending branch stayed without its honest evidence sentence). Pinned
 * here, four contracts in one suite:
 * ① the whitelist narrows the run face — the plane prompt carries ONLY the
 * selected chunk contents, the unselected blocks are never re-read;
 * ② kept-pending lands honest: the selected chunk still without a verbatim
 * citation keeps state pending, its note survives untouched and the
 * existing set-equality replay guard books zero rows — R28 posture);
 * ③ the unselected blocks carry over from the previous ledger row, so a
 * supplement of one gap can never silently drop another gap from the
 * preview banner;
 * ④ a whitelist that matches no current chunk row fails honestly (round
 * token guard + human repair path), it never degrades into a full run.
 * <p>L2 posture: transport and door mocked (infrastructure isolation, same
 * face as every other DocExtract suite); the real chain lives in the shipped
 * live journey test_extract_residual_banner_journey.py.
 */
class DocExtractDirectedSupplementTest {

    private static final long DOC = 9931L;
    private static final long PROJECT = 9932L;
    private static final String TOKEN = "gen-token-directed-supplement-001";

    private static final String TEXT_LOGIN = "用户连续输错密码五次后账号锁定十分钟";
    private static final String TEXT_REPORT = "报表导出支持按月归档并保留最近二十四个月";
    private static final String TEXT_LOG = "操作日志在线保留期限不少于六个月";

    /** One verbatim citation of the selected report chunk (anchor-gate clean). */
    private static final String JSON_CITE_REPORT = "{\"requirements\":[{"
            + "\"title\":\"报表按月归档\",\"acceptanceCriteria\":\"保留二十四个月\","
            + "\"chapter\":\"报表\",\"confidence\":0.9,\"evidence\":\"" + TEXT_REPORT + "\"}]}";

    /** The shipped unproductive-block face: a placeholder row that quotes
     *  nothing verbatim (the schema guard rejects a bare empty array, live
     *  shape = a claim row the anchor gate drops and the ledger cannot
     *  attribute). */
    private static final String JSON_PLACEHOLDER = "{\"requirements\":[{"
            + "\"title\":\"本块无需求声明\",\"acceptanceCriteria\":\"\","
            + "\"chapter\":\"报表\",\"confidence\":0.2,"
            + "\"evidence\":\"（本块仅权限字段声明，无可测试需求句）\"}]}";

    /** Previous generation: block #1 (login) and block #2 (report) both pending. */
    private static final String PREVIOUS_STEPS = "["
            + "{\"title\":\"块#1\",\"state\":\"pending\",\"evidence\":\"\","
            + "\"note\":\"残账章节：登录；未提取任何条目\"},"
            + "{\"title\":\"块#2\",\"state\":\"pending\",\"evidence\":\"\","
            + "\"note\":\"残账章节：报表；未提取任何条目\"}]";

    private static Map<String, Object> chunk(long id, int seq, String chapter, String content) {
        return DocExtractTestFixtures.chunkWithId(id, seq, chapter, content);
    }

    /**
     * Mock trio + a plane transport that answers every call with {@code json}
     * while recording every request face (the prompt-membership assertions).
     */
    private record Rig(CoreGateway coreGateway, LlmFallbackChain llmChain,
                       ProjectLlmRouter llmRouter, DocExtractLedgerStore ledgerStore,
                       List<ChatCompletionRequest> planeRequests) {
    }

    private final AtomicReference<String> planeReply = new AtomicReference<>(JSON_PLACEHOLDER);

    private Rig rig(List<Map<String, Object>> chunks) {
        CoreGateway coreGateway = mock(CoreGateway.class);
        LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
        ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
        DocExtractLedgerStore ledgerStore = mock(DocExtractLedgerStore.class);
        when(llmRouter.route(PROJECT)).thenReturn(DocExtractTestFixtures.endpoint());
        when(coreGateway.getDocumentChunks(DOC)).thenReturn(chunks);
        List<ChatCompletionRequest> seen = Collections.synchronizedList(new ArrayList<>());
        Function<ChatCompletionRequest, LlmChatResult> reply = request -> {
            seen.add(request);
            return new LlmChatResult(planeReply.get(), "openai", "routed-model", 50, 20, 3L);
        };
        when(llmChain.chat(any(), eq(PROJECT), any(LlmFallbackNotice.class), anyBoolean(), any()))
                .thenAnswer(inv -> reply.apply(inv.getArgument(0)));
        when(llmChain.chatStreamHandle(any(), eq(PROJECT), any(LlmFallbackNotice.class),
                any(), any())).thenAnswer(inv -> new LlmChatHandle(
                        reply.apply(inv.getArgument(0)), DocExtractTestFixtures.endpoint()));
        return new Rig(coreGateway, llmChain, llmRouter, ledgerStore, seen);
    }

    private static AiDocExtractMessage directed(List<Long> chunkIds) {
        return new AiDocExtractMessage(DOC, PROJECT, 1L, null, TOKEN, null, chunkIds);
    }

    private String capturedSteps(Rig rig) {
        ArgumentCaptor<String> steps = ArgumentCaptor.forClass(String.class);
        verify(rig.ledgerStore()).upsertIfChanged(eq(DOC), eq(TOKEN), steps.capture());
        return steps.getValue();
    }

    @Test
    void whitelistNarrowsTheRunPlaneToTheSelectedChunkOnly() {
        planeReply.set(JSON_CITE_REPORT);
        Rig rig = rig(List.of(
                chunk(1L, 1, "登录", TEXT_LOGIN),
                chunk(2L, 2, "报表", TEXT_REPORT),
                chunk(3L, 3, "日志", TEXT_LOG)));
        DocExtractServiceImpl service = DocExtractTestSupport.wiredService(
                rig.coreGateway(), rig.llmChain(), rig.llmRouter(), rig.ledgerStore());

        service.extract(directed(List.of(2L)));

        assertFalse(rig.planeRequests().isEmpty(), "定向补提轮必须有一轮外呼");
        for (ChatCompletionRequest request : rig.planeRequests()) {
            String user = request.messages().get(1).content();
            assertTrue(user.contains(TEXT_REPORT),
                    "选中的块正文必须进入本轮提示词: " + user);
            assertFalse(user.contains(TEXT_LOGIN),
                    "未选中的块不得被重读（全篇重跑=成本缺口本体）: " + user);
            assertFalse(user.contains(TEXT_LOG),
                    "未选中的块不得被重读（全篇重跑=成本缺口本体）: " + user);
        }
        String steps = capturedSteps(rig);
        assertTrue(steps.contains("块#2"), "选中块必须落账: " + steps);
        assertFalse(steps.contains("块#1") || steps.contains("块#3"),
                "本轮没有重读的块不得被伪造进本轮步集（上一代的账由③承接）: " + steps);
    }

    @Test
    void keptPendingSelectedChunkGainsHonestEvidenceAndKeepsItsNote() {
        planeReply.set(JSON_PLACEHOLDER);
        Rig rig = rig(List.of(
                chunk(1L, 1, "登录", TEXT_LOGIN),
                chunk(2L, 2, "报表", TEXT_REPORT),
                chunk(3L, 3, "日志", TEXT_LOG)));
        DocExtractServiceImpl service = DocExtractTestSupport.wiredService(
                rig.coreGateway(), rig.llmChain(), rig.llmRouter(), rig.ledgerStore());

        service.extract(directed(List.of(2L)));

        String steps = capturedSteps(rig);
        assertTrue(steps.contains("块#2"), "选中块必须落账: " + steps);
        assertTrue(steps.contains("复跑一轮仍无条目"),
                "复跑仍零产出的选中块 pending 步必须带上诚实句: " + steps);
        assertTrue(steps.contains("残账章节"),
                "pending 步的 note 不得被重写（章节残账仍在上屏通道里）: " + steps);
        assertTrue(steps.contains("pending"), "零产出块保持 pending 不伪 done: " + steps);
    }

    @Test
    void unselectedPendingBlocksCarryOverFromThePreviousLedgerRow() {
        planeReply.set(JSON_CITE_REPORT);
        Rig rig = rig(List.of(
                chunk(1L, 1, "登录", TEXT_LOGIN),
                chunk(2L, 2, "报表", TEXT_REPORT),
                chunk(3L, 3, "日志", TEXT_LOG)));
        when(rig.ledgerStore().findLatestStepsJson(DOC)).thenReturn(PREVIOUS_STEPS);
        DocExtractServiceImpl service = DocExtractTestSupport.wiredService(
                rig.coreGateway(), rig.llmChain(), rig.llmRouter(), rig.ledgerStore());

        service.extract(directed(List.of(2L)));

        String steps = capturedSteps(rig);
        assertTrue(steps.contains("块#1"),
                "未选中块的上一代 pending 步必须承接（补提一块禁让另一块的缺口静默消失）: " + steps);
        assertTrue(steps.contains("残账章节：登录"),
                "承接步的 note 原样保留: " + steps);
        assertTrue(steps.contains("块#2"), "选中块的本轮结果覆盖上一代: " + steps);
        assertFalse(steps.contains("块#3"),
                "既未选中、上一代也无记录的块不得进入本代账本: " + steps);
        assertFalse(steps.contains("块#2\",\"state\":\"pending"),
                "选中块本轮有逐字引用即归 done: " + steps);
    }

    @Test
    void whitelistMatchingNoChunkFailsHonestlyWithoutAFullRun() {
        Rig rig = rig(List.of(chunk(1L, 1, "登录", TEXT_LOGIN)));
        DocExtractServiceImpl service = DocExtractTestSupport.wiredService(
                rig.coreGateway(), rig.llmChain(), rig.llmRouter(), rig.ledgerStore());

        assertThrows(RuntimeException.class, () -> service.extract(directed(List.of(999L))));

        verify(rig.coreGateway()).markExtractionFailed(eq(DOC),
                contains("999"), eq(TOKEN));
        assertEquals(0, rig.planeRequests().size(),
                "白名单落空时禁退化为全篇重跑（零外呼即止损）");
    }
}
