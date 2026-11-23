package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.sixbean.testpike.aiservice.doc.domain.component.ContextBudgetPlanner;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.CHAPTER_MARK;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunkWithId;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;

/**
 * root cause F2 — an impossible level-wise overview plan must degrade
 * the overview round only, never abort the whole extraction.
 *
 * <p>Since the chapter overview is a zero-consumer artefact on the
 * requirement path (per-group RAW extraction feeds the enumeration task), yet
 * {@code MultiLevelReducePlanner.plan} used to throw a ServiceException when
 * the group count busts the reduce capacity (40 groups need 3 levels &gt;
 * max-levels 2), which the outer catch turned into a failed extraction with
 * zero requirement points. The ruled posture (EE adjudication doc): WARN and
 * a step process event name "overview not generated + reason", skip the
 * overview round, the requirement plane still rides rawGroups.
 *
 * <p>Fixture face: 40 tiny chapters with the group ceiling pinned to one
 * chunk per group, so the grouping plane deterministically yields 40 groups;
 * every per-group reply cites evidence verbatim from its own raw chunk, so
 * the anchor gate lands all 40 items (an empty landing here would mean
 * the overview skip silently broke attribution — pinned red on purpose).
 */
class DocExtractOverviewRoundDegradationTest {

    private static final long DOC_ID = 101L;
    private static final long PROJECT_ID = 41L;
    private static final String TOKEN = "gen-token-overview-degrade-001";

    private final DocExtractTestFixtures fixtures =
            new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final DocExtractServiceImpl service = fixtures.pipeline();

    /** Provider requests that crossed the plane fakes, in call order. */
    private final java.util.List<ChatCompletionRequest> planeRequests =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @Test
    void impossibleOverviewPlanDegradesTheRoundButNeverAbortsExtraction() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        // One chunk per group -> exactly 40 groups -> plan(40) needs 3 levels
        // while reduce-max-levels stays on its factory 2 (capacity refusal).
        when(coreGateway.getSysConfig(ContextBudgetPlanner.KEY_GROUP_MAX_CHUNKS))
                .thenReturn("1");
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(
                java.util.stream.IntStream.rangeClosed(1, 40)
                        .mapToObj(i -> chunkWithId(200L + i, i, "第" + i + "章",
                                "第" + i + "章锚句：本章独有提取线索" + "字".repeat(6000)))
                        .toList());
        fixtures.stubPlaneTransports(this::reply);

        assertDoesNotThrow(() -> service.extract(fixtures.message()),
                "概览轮容量拒止不得中止整次提取（T589 F2 降级令）");

        // Overview round skipped entirely: zero summary-plane traffic.
        assertTrue(planeRequests.stream()
                        .noneMatch(r -> r.messages().get(0).content()
                                .contains(DocExtractServiceImpl.SUMMARY_MARKER)),
                "降级=概览轮零调用，禁先烧摘要再失败");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), captor.capture(), eq(TOKEN));
        List<Map<String, Object>> items = captor.getValue();
        assertEquals(40, items.size(),
                "需求点必须走逐组原文直提落账（空账=降级把消费面一起吞了）: " + items.size());
        for (Map<String, Object> item : items) {
            assertTrue(item.get("chunkId") instanceof Number,
                    "逐组原文面每条必须带真实证据锚: " + item.get("title"));
        }
        verify(coreGateway, never()).markExtractionFailed(eq(DOC_ID),
                org.mockito.ArgumentMatchers.anyString(), eq(TOKEN));
    }

    /** Non-summary calls answer one unique item whose evidence quotes the
     *  raw chunk of exactly the chapter visible in the prompt. */
    private LlmChatResult reply(ChatCompletionRequest request) {
        planeRequests.add(request);
        String user = request.messages().get(1).content();
        Matcher mark = CHAPTER_MARK.matcher(user);
        if (!mark.find()) {
            return new LlmChatResult("{\"requirements\":[]}", "openai", "routed-model", 10, 5, 1L);
        }
        String chapter = mark.group(1);
        return new LlmChatResult("{\"requirements\":[{\"title\":\"需求点第" + chapter
                + "章\",\"acceptanceCriteria\":\"线索齐全\",\"chapter\":\"第" + chapter
                + "章\",\"confidence\":0.9,\"evidence\":\"第" + chapter + "章锚句\"}]}",
                "openai", "routed-model", 100, 50, 20L);
    }
}
