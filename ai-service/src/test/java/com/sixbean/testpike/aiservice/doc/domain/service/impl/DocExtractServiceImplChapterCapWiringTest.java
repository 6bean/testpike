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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.CHAPTER_MARK;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.chunkWithId;
import static com.sixbean.testpike.aiservice.doc.domain.service.impl.DocExtractTestFixtures.endpoint;

/**
 * assembly wiring — the chapter-span cap hot key
 * ai.doc.group-max-chapters must reach the grouping plane per extraction.
 *
 * <p>The mechanism face (ContextBudgetPlanner seals a group the moment a NEW
 * chapter would push its distinct chapter count past the cap, never inside a
 * single chapter) is pinned in ContextBudgetGroupGranularityTest. This class
 * pins the consumption seam: a small five-chapter document where only the
 * chapter dimension can split the groups (token/chunk ceilings stay far above
 * the fixture size), so the sealed group count IS the wiring verdict.
 *
 * <p>Fail-closed face: an unset key answers the factory cap (20) silently and
 * a garbage value fails closed to it with a WARN — both keep the single-group
 * byte face, the same discipline as the node-cap siblings.
 */
class DocExtractServiceImplChapterCapWiringTest {

    private static final long DOC_ID = 108L;
    private static final long PROJECT_ID = 48L;
    private static final String TOKEN = "gen-token-chaptercap-001";

    private final DocExtractTestFixtures fixtures =
            new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final DocExtractServiceImpl service = fixtures.pipeline();

    /** Enumeration-plane prompts (summary round excluded), in call order. */
    private final java.util.List<String> planePrompts =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private void stubFiveSmallChapters(String chapterCap) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getSysConfig(ContextBudgetPlanner.KEY_GROUP_MAX_CHAPTERS))
                .thenReturn(chapterCap);
        // 5 chapters x ~1,600 chars: the whole document busts the group budget
        // (entry pre-pass routes the run through per-group raw extraction) while
        // two chapters (~3,300 chars) stay far INSIDE it — with the cap in play
        // groups seal at {1,2}{3,4}{5}; with the factory cap the char ceiling
        // only welds {1,2,3,4}{5}. The group count IS the wiring verdict.
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(
                java.util.stream.IntStream.rangeClosed(1, 5)
                        .mapToObj(i -> chunkWithId(800L + i, i, "第" + i + "章",
                                "第" + i + "章锚句：本章独有提取线索" + "字".repeat(1600)))
                        .toList());
        fixtures.stubPlaneTransports(this::reply);
    }

    @Test
    void hotChapterCapSealsGroupsOnTheAssemblyPlane() {
        stubFiveSmallChapters("2");

        service.extract(fixtures.message());

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), captor.capture(), eq(TOKEN));
        List<Map<String, Object>> items = captor.getValue();
        // Cap 2 over five chapters -> groups {1,2} {3,4} {5}: one item per group.
        assertEquals(3, items.size(),
                "章跨度上限 2 必须逐组封组落账（1 组=热键没接进装配链）: " + items.size());
        for (String prompt : planePrompts) {
            long chapters = distinctChapters(prompt);
            assertEquals(true, chapters <= 2,
                    "任何一组的提示词最多携带 2 个不同章节，实测 " + chapters + " 个");
        }
        long covered = planePrompts.stream().flatMap(p -> chaptersOf(p).stream())
                .distinct().count();
        assertEquals(5, covered, "五章内容必须恰好各入一组，禁静默丢章");
    }

    @Test
    void unsetCapKeepsTheFactorySingleGroupFace() {
        stubFiveSmallChapters(null);

        service.extract(fixtures.message());

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), captor.capture(), eq(TOKEN));
        // Factory cap 20 >= five chapters -> only the char ceiling welds
        // {1,2,3,4}{5}: two groups, byte face unchanged for this fixture.
        assertEquals(2, captor.getValue().size(),
                "键缺行=出厂 20 上限，五章只按预算封两组零漂移: " + captor.getValue().size());
    }

    @Test
    void garbageCapFailsClosedToFactoryWithoutAborting() {
        stubFiveSmallChapters("junk");

        service.extract(fixtures.message());

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), captor.capture(), eq(TOKEN));
        assertEquals(2, captor.getValue().size(),
                "非法值 fail-closed 回退出厂 20，预算封口面零漂移: " + captor.getValue().size());
    }

    private static java.util.List<String> chaptersOf(String prompt) {
        java.util.List<String> seen = new java.util.ArrayList<>();
        Matcher mark = CHAPTER_MARK.matcher(prompt);
        while (mark.find()) {
            String chapter = "第" + mark.group(1) + "章";
            if (!seen.contains(chapter)) {
                seen.add(chapter);
            }
        }
        return seen;
    }

    private static long distinctChapters(String prompt) {
        return chaptersOf(prompt).size();
    }

    /** Non-summary calls answer one unique item quoting the first chapter
     *  visible in the prompt, so the landed item count mirrors the group count. */
    private LlmChatResult reply(ChatCompletionRequest request) {
        String system = request.messages().get(0).content();
        String user = request.messages().get(1).content();
        if (system.contains(DocExtractServiceImpl.SUMMARY_MARKER)) {
            return new LlmChatResult("{\"summary\":\"章节概览\"}", "openai", "routed-model",
                    10, 5, 1L);
        }
        Matcher mark = CHAPTER_MARK.matcher(user);
        if (!mark.find()) {
            return new LlmChatResult("{\"requirements\":[]}", "openai", "routed-model",
                    10, 5, 1L);
        }
        String chapter = mark.group(1);
        planePrompts.add(user);
        return new LlmChatResult("{\"requirements\":[{\"title\":\"需求点第" + chapter
                + "章\",\"acceptanceCriteria\":\"线索齐全\",\"chapter\":\"第" + chapter
                + "章\",\"confidence\":0.9,\"evidence\":\"第" + chapter + "章锚句\"}]}",
                "openai", "routed-model", 100, 50, 20L);
    }
}
