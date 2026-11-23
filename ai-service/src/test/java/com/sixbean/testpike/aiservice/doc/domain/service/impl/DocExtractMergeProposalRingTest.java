package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunkWithId;

/**
 * the cross-group merge proposal runs through the shared
 * kernel deliberation ring (propose one merged item, verify the claims
 * against the colliding rows, revise the named disputes) instead of the
 * mechanical first-seen fold alone.
 *
 * <p>Pinned behaviours:
 * <ul>
 * <li>a colliding title gets exactly one merge-proposal deliberation and,
 * once the ring converges, the merged row replaces the first-seen row
 * while the other rows keep their order;</li>
 * <li>a ring that never converges falls back to the first-seen fold — an
 * unsettled merge must never drop or garble items (fail-soft, same
 * discipline as the reconcile ring);</li>
 * <li>no collisions means zero surplus provider calls — the ring costs
 * nothing on a duplicate-free plane.</li>
 * </ul>
 *
 * <p>L2 isolation on infrastructure seams (gateway door, provider chain,
 * router), identical fixture face as the sibling grouped-raw tests.
 */
class DocExtractMergeProposalRingTest {

    private static final long DOC_ID = 98L;
    private static final long PROJECT_ID = 38L;
    private static final String TOKEN = "gen-token-merge-ring-001";
    /** Marker the production merge prompt must carry (single source = impl). */
    private static final String MERGE_MARK = DocExtractServiceImpl.MERGE_INSTRUCTION;

    private static final String JSON_GROUP_1 = """
            {"requirements":[
              {"title":"共享标题","acceptanceCriteria":"连续五次错误锁定","chapter":"第一章","confidence":0.9,\
               "evidence":"锁定异常登录策略"},
              {"title":"组一独占","acceptanceCriteria":"密码复杂度校验","chapter":"第一章","confidence":0.85,\
               "evidence":"第一章锚句"}]}""";

    private static final String JSON_GROUP_2 = """
            {"requirements":[
              {"title":"共享标题","acceptanceCriteria":"重复报出的同一需求","chapter":"第二章","confidence":0.9,\
               "evidence":"双因素验证码"},
              {"title":"组二独占","acceptanceCriteria":"短信二次确认","chapter":"第二章","confidence":0.8,\
               "evidence":"第二章锚句"}]}""";

    /** Converged merge answer: one row, same title, union of the testable
     *  constraints, evidence verbatim from the group-one raw chunk. */
    private static final String JSON_MERGED = """
            {"requirements":[
              {"title":"共享标题","acceptanceCriteria":"连续五次错误锁定并触发账户冻结；重复报出的同一需求",\
               "chapter":"第一章","confidence":0.9,"evidence":"锁定异常登录策略"}]}""";

    /** A merge answer that does not honour the cluster title — the verifier
     *  must dispute it, the ring must fall back to the first-seen fold. */
    private static final String JSON_MERGED_OFF_TITLE = """
            {"requirements":[
              {"title":"跑题的合并","acceptanceCriteria":"无关约束","chapter":"第一章","confidence":0.5,\
               "evidence":"第一章锚句"}]}""";

    private final DocExtractTestFixtures fixtures =
            new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final DocExtractServiceImpl service =
            new DocExtractServiceImpl(coreGateway, llmChain, llmRouter, new ObjectMapper());

    private final java.util.List<ChatCompletionRequest> planeRequests =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private void stubPlanes(String mergeAnswer) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunkWithId(21L, 1, "第一章", "第一章锚句：锁定异常登录策略" + "字".repeat(100_000)),
                chunkWithId(22L, 2, "第二章", "第二章锚句：双因素验证码" + "字".repeat(100_000))));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenAnswer(inv -> reply(inv.getArgument(0, ChatCompletionRequest.class), mergeAnswer));
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), any()))
                .thenAnswer(inv -> new com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle(
                        reply(inv.getArgument(0, ChatCompletionRequest.class), mergeAnswer), endpoint()));
    }

    private LlmChatResult reply(ChatCompletionRequest request, String mergeAnswer) {
        planeRequests.add(request);
        String system = request.messages().get(0).content();
        if (system.contains(DocExtractServiceImpl.SUMMARY_MARKER)) {
            return new LlmChatResult("转述概览文本", "openai", "routed-model", 10, 5, 1L);
        }
        String user = request.messages().get(1).content();
        if (user.contains(MERGE_MARK)) {
            return new LlmChatResult(mergeAnswer, "openai", "routed-model", 20, 10, 5L);
        }
        String content = user.contains("第一章") ? JSON_GROUP_1
                : user.contains("第二章") ? JSON_GROUP_2
                : "{\"requirements\":[]}";
        return new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L);
    }

    private List<Map<String, Object>> runAndCapture() {
        service.extract(fixtures.message());
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), captor.capture(), eq(TOKEN));
        return captor.getValue();
    }

    @Test
    void convergedMergeReplacesTheFirstSeenRow() {
        stubPlanes(JSON_MERGED);
        List<Map<String, Object>> items = runAndCapture();

        assertEquals(3, items.size(), "归并只作用于冲突簇，其余条目保持首见顺序: " + items);
        assertEquals("共享标题", items.get(0).get("title"));
        assertTrue(String.valueOf(items.get(0).get("acceptanceCriteria")).contains("账户冻结"),
                "收敛的归并建议必须替换首见行，合并全部可测试约束: " + items.get(0));
        assertEquals(21L, items.get(0).get("chunkId"),
                "归并行的证据锚仍回指原文块（evidence 逐字摘自组一原文）");
        long mergeCalls = planeRequests.stream()
                .filter(r -> r.messages().get(1).content().contains(MERGE_MARK))
                .count();
        assertTrue(mergeCalls >= 1, "跨组重复标题必须走归并建议推理确认环");
    }

    @Test
    void unsettledMergeFallsBackToFirstSeenFoldWithoutLosingItems() {
        stubPlanes(JSON_MERGED_OFF_TITLE);
        List<Map<String, Object>> items = runAndCapture();

        assertEquals(3, items.size(), "未收敛的环不得吞掉任何条目: " + items);
        // (merge-preserved markers): the first-seen fold
        // fallback now carries the dropped duplicate's acceptance text on the
        // survivor — the legacy bare first-seen value was pinned here before
        // identifiers in folded bodies had a survival duty.
        assertEquals("连续五次错误锁定；重复报出的同一需求",
                items.get(0).get("acceptanceCriteria"),
                "标题偏离的归并草案被验证方点名分歧后回退首见折叠，被折叠重复行正文并入存活行: "
                        + items.get(0));
        assertEquals(21L, items.get(0).get("chunkId"));
    }

    @Test
    void duplicateFreePlaneBurnsNoMergeCalls() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunkWithId(21L, 1, "第一章", "第一章锚句：锁定异常登录策略" + "字".repeat(100_000)),
                chunkWithId(22L, 2, "第二章", "第二章锚句：双因素验证码" + "字".repeat(100_000))));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                org.mockito.ArgumentMatchers.anyBoolean(), any()))
                .thenAnswer(inv -> replyNonSummary(inv.getArgument(0, ChatCompletionRequest.class)));
        when(llmChain.chatStreamHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                any(), any()))
                .thenAnswer(inv -> new com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle(
                        replyNonSummary(inv.getArgument(0, ChatCompletionRequest.class)), endpoint()));

        List<Map<String, Object>> items = runAndCapture();

        assertEquals(4, items.size(), "无重复标题=两组各两条全部保留: " + items);
        assertFalse(planeRequests.stream()
                        .anyMatch(r -> r.messages().get(1).content().contains(MERGE_MARK)),
                "零冲突簇必须零归并外呼，环不烧无谓成本");
    }

    private LlmChatResult replyNonSummary(ChatCompletionRequest request) {
        planeRequests.add(request);
        String system = request.messages().get(0).content();
        if (system.contains(DocExtractServiceImpl.SUMMARY_MARKER)) {
            return new LlmChatResult("转述概览文本", "openai", "routed-model", 10, 5, 1L);
        }
        String user = request.messages().get(1).content();
        String content = user.contains("第一章")
                ? JSON_GROUP_1.replace("共享标题", "组一标题")
                : user.contains("第二章")
                ? JSON_GROUP_2.replace("共享标题", "组二标题")
                : "{\"requirements\":[]}";
        return new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L);
    }
}
