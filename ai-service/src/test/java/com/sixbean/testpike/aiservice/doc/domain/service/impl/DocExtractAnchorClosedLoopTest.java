package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.EvidenceChunkMatcher;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunkWithId;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;

/**
 * evidence-anchor closed loop for the extraction plane
 * (ruling section four: live incident document 8639 landed 50 requirement
 * points with empty anchors, so the case plane could never inject original
 *
 * <p>Pinned behaviours:
 * <ul>
 * <li>an item whose evidence cannot be attributed to any source block is
 * held back from the landing write while the K8 hot key
 * {@code ai.doc.anchor-required.enabled} stands on (factory posture =
 * on: missing/blank/garbage keeps the gate);</li>
 * <li>only an explicit "false" restores the legacy write-everything face;</li>
 * <li>grouped-raw items are attributed against THEIR OWN group's block set
 * at production time — a group holding exactly one original block
 * anchors its paraphrased excerpts deterministically, no model self-
 * declaration involved;</li>
 * <li>an anchor once settled is never re-stamped by a later wider pass
 * (server-side attribution is first-writer-wins per item).</li>
 * </ul>
 *
 * <p>L2 isolation on the infrastructure seams (gateway door, provider chain,
 * router) with the same fixture face as the sibling grouped-raw tests.
 */
class DocExtractAnchorClosedLoopTest {

    private static final long DOC_ID = 98L;
    private static final long PROJECT_ID = 38L;
    private static final String TOKEN = "gen-token-anchor-001";

    /** One anchored excerpt (verbatim from chunk 21) + one that exists in no
     *  source block — the planted empty anchor that must never land. */
    private static final String JSON_MIXED = """
            {"requirements":[
              {"title":"令牌过期策略","acceptanceCriteria":"会话令牌五分钟后失效","chapter":"第一章",\
               "confidence":0.9,"evidence":"会话令牌五分钟过期"},
              {"title":"无锚条目","acceptanceCriteria":"复述性描述无法逐字回指原文","chapter":"第一章",\
               "confidence":0.8,"evidence":"这段摘录不存在于任何原文块集合之中"}]}""";

    private final DocExtractTestFixtures fixtures =
            new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final DocExtractServiceImpl service =
            new DocExtractServiceImpl(coreGateway, llmChain, llmRouter, new ObjectMapper());

    /** Small in-budget single-chunk document: the flat final-bucket plane
     *  answers the mixed item set, one of them unattributable. */
    private void stubFlatPlane() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunkWithId(21L, 1, "第一章", "短文本：会话令牌五分钟过期后必须重新认证")));
        fixtures.stubPlaneTransports(this::reply);
    }

    private LlmChatResult reply(ChatCompletionRequest request) {
        String system = request.messages().get(0).content();
        if (system.contains(DocExtractServiceImpl.SUMMARY_MARKER)) {
            return new LlmChatResult("转述概览文本", "openai", "routed-model", 10, 5, 1L);
        }
        return new LlmChatResult(JSON_MIXED, "openai", "routed-model", 100, 50, 20L);
    }

    private List<Map<String, Object>> capturedLanding() {
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), captor.capture(), eq(TOKEN));
        return captor.getValue();
    }

    @Test
    void unattributableItemIsHeldBackWhileAnchorGateStandsOn() {
        stubFlatPlane();

        service.extract(fixtures.message());

        List<Map<String, Object>> items = capturedLanding();
        assertEquals(1, items.size(),
                "植入空锚必红：默认开启的落库闸必须拦下无证据锚的条目: " + items);
        assertEquals("令牌过期策略", items.get(0).get("title"));
        assertEquals(21L, items.get(0).get("chunkId"));
    }

    @Test
    void explicitFalseKeepsTheLegacyWriteEverythingFace() {
        stubFlatPlane();
        when(coreGateway.getSysConfig("ai.doc.anchor-required.enabled")).thenReturn("false");

        service.extract(fixtures.message());

        List<Map<String, Object>> items = capturedLanding();
        assertEquals(2, items.size(), "关闸=旧形态原样入库（可回退红线）: " + items);
        assertEquals(java.util.Set.of("令牌过期策略", "无锚条目"),
                items.stream().map(row -> row.get("title")).collect(java.util.stream.Collectors.toSet()));
    }

    /** Grouped-raw face: two 100k-char original chunks group one-per-group;
     *  the second group's excerpt is a paraphrase that matches no text
     *  verbatim — sole-block attribution still anchors it to its own block. */
    @Test
    void groupedItemsAreAttributedAgainstTheirOwnGroupBlocks() {
        String group2Paraphrase = """
                {"requirements":[
                  {"title":"组二大意","acceptanceCriteria":"双因素验证的转述","chapter":"第二章",\
                   "confidence":0.8,"evidence":"第二章大意复述并非逐字原文"}]}""";
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                chunkWithId(11L, 1, "第一章", "第一章锚句：锁定异常登录策略" + "字".repeat(100_000)),
                chunkWithId(12L, 2, "第二章", "第二章锚句：双因素验证码" + "字".repeat(100_000))));
        fixtures.stubPlaneTransports(inv -> replyGrouped(inv, group2Paraphrase));

        service.extract(fixtures.message());

        List<Map<String, Object>> items = capturedLanding();
        assertEquals(2, items.size(), "组内归属后不得再有空锚被闸拦下: " + items);
        Map<String, Object> paraphrased = items.stream()
                .filter(row -> "组二大意".equals(row.get("title")))
                .findFirst().orElseThrow();
        assertEquals(12L, paraphrased.get("chunkId"),
                "单块组的转述摘录必须由服务端归属到本组唯一原文块（禁模型自报口径）");
    }

    private LlmChatResult replyGrouped(ChatCompletionRequest request, String group2Json) {
        String system = request.messages().get(0).content();
        if (system.contains(DocExtractServiceImpl.SUMMARY_MARKER)) {
            return new LlmChatResult("转述概览文本", "openai", "routed-model", 10, 5, 1L);
        }
        String user = request.messages().get(1).content();
        String content = user.contains("第一章")
                ? """
                  {"requirements":[
                    {"title":"组一独占","acceptanceCriteria":"密码复杂度校验","chapter":"第一章",\
                     "confidence":0.85,"evidence":"第一章锚句"}]}"""
                : user.contains("第二章") ? group2Json : "{\"requirements\":[]}";
        return new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L);
    }

    /** Matcher unit face: attribution is first-writer-wins — a later, wider
     *  pass must never re-stamp an already settled anchor. */
    @Test
    void settledAnchorSurvivesASecondWiderAttributionPass() {
        EvidenceChunkMatcher matcher = new EvidenceChunkMatcher();
        List<Map<String, Object>> chunks = List.of(
                Map.of("id", 9L, "content", "第一段原文：审计日志保留一百八十天"));
        Map<String, Object> settled = new HashMap<>();
        settled.put("title", "已定锚条目");
        settled.put("evidence", "审计日志保留一百八十天");
        settled.put("chunkId", 5L);
        Map<String, Object> open = new HashMap<>();
        open.put("title", "未定锚条目");
        open.put("evidence", "审计日志保留一百八十天");

        matcher.attachChunkIds(chunks, List.of(settled, open));

        assertEquals(5L, settled.get("chunkId"), "已归属的锚禁被第二遍改写（服务端首写者单源）");
        assertEquals(9L, open.get("chunkId"));
    }
}
