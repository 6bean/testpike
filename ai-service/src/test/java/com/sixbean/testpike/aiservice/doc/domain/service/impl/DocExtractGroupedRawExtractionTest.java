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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunkWithId;

/**
 * the over-budget Map side extracts requirement items from
 * the ORIGINAL text group by group instead of from a second-hand summary
 * package (fidelity root cause C2: an enumeration task was fed a paraphrase).
 *
 * <p>Pinned behaviours:
 * <ul>
 * <li>every final-extract request carries raw chunk text (chapter anchor
 * sentences), never only the summarised blocks;</li>
 * <li>items surfacing in several groups are folded by the shared
 * {@code TitleIdempotentMerge.foldFirstSeenByTitle} single source —
 * first seen wins, no second merge implementation;</li>
 * <li>evidence anchors resolve against the raw group chunks, so folded
 * items carry a real chunkId where the summary
 * package could only ever answer null.</li>
 * </ul>
 *
 * <p>L2 isolation on infrastructure seams (gateway door, provider chain,
 * router), same fixture face as the sibling resume tests: tokenCounter/
 * cache/ledger bridges null, two 100k-char chunks group into 2 groups at the
 * default char budget.
 */
class DocExtractGroupedRawExtractionTest {

    private static final long DOC_ID = 97L;
    private static final long PROJECT_ID = 37L;
    private static final String TOKEN = "gen-token-grouped-raw-001";

    /** Group-one reply: the shared title is offered here first, both evidence
     *  excerpts are verbatim from the first raw chunk. */
    private static final String JSON_GROUP_1 = """
            {"requirements":[
              {"title":"共享标题","acceptanceCriteria":"连续五次错误锁定","chapter":"第一章","confidence":0.9,\
               "evidence":"锁定异常登录策略"},
              {"title":"组一独占","acceptanceCriteria":"密码复杂度校验","chapter":"第一章","confidence":0.85,\
               "evidence":"第一章锚句"}]}""";

    /** Group-two reply: the same shared title appears again (must fold away),
     *  evidence excerpts are verbatim from the second raw chunk. */
    private static final String JSON_GROUP_2 = """
            {"requirements":[
              {"title":"共享标题","acceptanceCriteria":"重复报出的同一需求","chapter":"第二章","confidence":0.9,\
               "evidence":"双因素验证码"},
              {"title":"组二独占","acceptanceCriteria":"短信二次确认","chapter":"第二章","confidence":0.8,\
               "evidence":"第二章锚句"}]}""";

    private final DocExtractTestFixtures fixtures =
            new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final DocExtractServiceImpl service =
            new DocExtractServiceImpl(coreGateway, llmChain, llmRouter, new ObjectMapper());

    /** Every provider request that crossed either plane fake, in call order.
     *  Recorded inside the answer so the assertion never bets on which
     *  transport (streaming vs blocking) the plane actually chose. */
    private final java.util.List<ChatCompletionRequest> planeRequests =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** The summary plane keeps producing the chapter overview (retained by
     *  adjudication, no longer the requirement-point input); every non-summary
     *  call answers per the raw anchor visible in its prompt. */
    private void stubPlanes() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunkWithId(11L, 1, "第一章", "第一章锚句：锁定异常登录策略" + "字".repeat(100_000)),
                chunkWithId(12L, 2, "第二章", "第二章锚句：双因素验证码" + "字".repeat(100_000))));
        fixtures.stubPlaneTransports(this::reply);
    }

    private LlmChatResult reply(ChatCompletionRequest request) {
        planeRequests.add(request);
        String system = request.messages().get(0).content();
        if (system.contains(DocExtractServiceImpl.SUMMARY_MARKER)) {
            return new LlmChatResult("转述概览文本", "openai", "routed-model", 10, 5, 1L);
        }
        String user = request.messages().get(1).content();
        String content = user.contains("第一章") ? JSON_GROUP_1
                : user.contains("第二章") ? JSON_GROUP_2
                : "{\"requirements\":[]}";
        return new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L);
    }

    @Test
    void overBudgetRunFeedsRawGroupTextAndFoldsDuplicateTitles() {
        stubPlanes();

        service.extract(fixtures.message());

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), captor.capture(), eq(TOKEN));
        List<Map<String, Object>> items = captor.getValue();

        assertEquals(3, items.size(), "跨组重复标题必须按幂等键折叠: " + items);
        assertEquals("共享标题", items.get(0).get("title"));
        assertEquals("组一独占", items.get(1).get("title"));
        assertEquals("组二独占", items.get(2).get("title"));

        // Anchor face = original group chunks: the first-seen row cites group 1.
        assertEquals(11L, items.get(0).get("chunkId"),
                "证据锚必须回指原文块（T585 前置形态），摘要包无行 id 只能落 null");
        assertEquals(11L, items.get(1).get("chunkId"));
        assertEquals(12L, items.get(2).get("chunkId"));

        // The requirement plane actually saw raw text, not the paraphrase.
        List<String> extractPrompts = planeRequests.stream()
                .filter(r -> !r.messages().get(0).content().contains(DocExtractServiceImpl.SUMMARY_MARKER))
                .map(r -> r.messages().get(1).content())
                .toList();
        assertTrue(extractPrompts.size() >= 2,
                "逐组直提=每组一次需求点平面调用，实际非摘要调用数=" + extractPrompts.size());
        assertTrue(extractPrompts.stream().anyMatch(p -> p.contains("第一章锚句")),
                "逐组直提必须把第一章原文喂给需求点平面");
        assertTrue(extractPrompts.stream().anyMatch(p -> p.contains("第二章锚句")),
                "逐组直提必须把第二章原文喂给需求点平面");
        assertNotNull(items.get(0).get("evidence"));
    }

    /** merge-preserved markers: when a duplicate title
 * folds away, the dropped row's own acceptance text must survive on the
 * survivor, so identifiers planted in duplicate bodies (the golden
 * window's red B syn_absorbed) are never swallowed by the fold. The
 * merge proposal instruction carries the same duty for the ring. */
    @Test
    void foldedDuplicateTitleKeepsTheDroppedRowAcceptanceTextOnTheSurvivor() {
        stubPlanes();

        service.extract(fixtures.message());

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), captor.capture(), eq(TOKEN));
        List<Map<String, Object>> items = captor.getValue();

        Map<String, Object> survivor = items.stream()
                .filter(row -> "共享标题".equals(row.get("title")))
                .findFirst().orElseThrow();
        String acceptance = String.valueOf(survivor.get("acceptanceCriteria"));
        assertTrue(acceptance.contains("连续五次错误锁定"),
                "存活行必须保住自己的正文: " + acceptance);
        assertTrue(acceptance.contains("重复报出的同一需求"),
                "被折叠重复行的正文必须并入存活行，编号标识才可能被召回: " + acceptance);
        assertEquals(11L, survivor.get("chunkId"),
                "并入他行文字不得改写存活行自己的证据锚");
        assertTrue(DocExtractServiceImpl.MERGE_INSTRUCTION.contains("逐字保留"),
                "归并建议指令必须要求合并行逐字保留源行编号标识: "
                        + DocExtractServiceImpl.MERGE_INSTRUCTION);
    }
}
