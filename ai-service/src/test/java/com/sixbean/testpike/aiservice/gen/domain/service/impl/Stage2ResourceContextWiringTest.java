package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.FidelityGate;
import com.sixbean.testpike.aiservice.gen.domain.component.GenStructuredOutput;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptLimitResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage-2 context contract items 4 and 5 : the script pass
 * must know the project's referable resource pool (environments / data
 * factories / API collections, all over EXISTING core internal doors — zero
 * new endpoints) so it cannot invent dangling resource names, and the case
 * level few-shot segment (single source {@link StyleFewshotBridge}, never a
 * second retrieval component) must ride every stage-2 round.
 * <p>Discipline mirrors segment A: one computation per task before the ring
 * (AP-6 byte-stable across ring rounds), fail-soft to the empty segment on an
 * empty pool or any door failure — the legacy prompt bytes stay untouched.
 * L2 posture: infrastructure mocks via {@link GenWiringHarness} single source;
 * every judgement component stays the real one.
 */
class Stage2ResourceContextWiringTest {

    private static final long TASK_ID = 162L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long CASE_ID = 5603L;

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();
    private final LlmFallbackChain llmChain = GenWiringHarness.mockLlmChain();
    private final ProjectLlmRouter llmRouter = GenWiringHarness.mockLlmRouter();
    private final StyleFewshotBridge fewshotBridge = mock(StyleFewshotBridge.class);

    private final List<String> scriptPrompts = new ArrayList<>();

    /** Minimal stored-case row in the core CaseInfo JSON shape (getCase door). */
    private static Map<String, Object> caseRow() {
        return Map.of(
                "id", CASE_ID,
                "title", "下单流程",
                "precondition", "用户已登录",
                "expectedResult", "订单创建成功",
                "priority", 1,
                "confidence", 2,
                "endType", "web",
                "steps", List.of(Map.of("stepNo", 1, "description", "点击立即购买按钮", "isAssert", 0)));
    }

    @BeforeEach
    void wireInfrastructure() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        when(coreGateway.getSysConfig(GenStructuredOutput.GATE_KEY)).thenReturn("0");
        // Fold the slice-A repair history door out of this suite's accounting:
        // cap 0 = segment off and no ledger read (gate semantics, not a mock).
        when(coreGateway.getSysConfig("ai.repair.rounds")).thenReturn("0");
        when(coreGateway.getCase(CASE_ID)).thenReturn(caseRow());
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    scriptPrompts.add(r.messages().get(1).content());
                    return GenWiringHarness.handle(GenWiringHarness.STAGE2_CLEAN);
                });
    }

    private AiGenServiceImpl service() {
        return new AiGenServiceImpl(llmChain, llmRouter, coreGateway, new ObjectMapper(),
                new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(coreGateway), null, null, null, null, fewshotBridge);
    }

    private void regenerateCase() {
        service().generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                2, "", null, CASE_ID));
    }

    private static List<Map<String, Object>> namedRows(String... names) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String name : names) {
            rows.add(Map.of("name", name));
        }
        return rows;
    }

    private static void stubFullPool(CoreGateway gateway) {
        when(gateway.listProjectEnvs(PROJECT_ID)).thenReturn(
                List.of(new CoreGateway.EnvBrief(7L, 1, "http://shop.test", "env")));
        when(gateway.listProjectDataFactories(eq(PROJECT_ID), anyLong())).thenReturn(
                namedRows("订单造数"));
        when(gateway.listProjectApiCollections(eq(PROJECT_ID), anyLong())).thenReturn(
                namedRows("交易接口集"));
    }

    @Test
    void resourceSegmentForwardsTaskOperatorThroughBothListDoors() {
        // segment B follow-up: the ai-service pull must ride X-Operator-Id
        // (task initiator from the MQ message) so core doors never see an
        // anonymous internal read — same transparency posture as runAnalysis.
        stubFullPool(coreGateway);

        String segment = AiGenServiceImpl.projectResourceSegment(
                coreGateway, PROJECT_ID, "", OPERATOR_ID);

        assertTrue(segment.contains("订单造数"), "factory row missing: " + segment);
        assertTrue(segment.contains("交易接口集"), "collection row missing: " + segment);
        verify(coreGateway).listProjectDataFactories(PROJECT_ID, OPERATOR_ID);
        verify(coreGateway).listProjectApiCollections(PROJECT_ID, OPERATOR_ID);
    }

    @Test
    void resourceSegmentNamesEnvFactoryAndCollection() {
        stubFullPool(coreGateway);

        String segment = AiGenServiceImpl.projectResourceSegment(coreGateway, PROJECT_ID);

        assertTrue(segment.contains("http://shop.test"), "env base URL missing: " + segment);
        assertTrue(segment.contains("订单造数"), "data factory name missing: " + segment);
        assertTrue(segment.contains("交易接口集"), "collection name missing: " + segment);
    }

    @Test
    void oversizedPoolCapsTheListedItems() {
        List<Map<String, Object>> factories = new ArrayList<>();
        for (int i = 1; i <= 45; i++) {
            factories.add(Map.of("name", "工厂" + i));
        }
        when(coreGateway.listProjectDataFactories(eq(PROJECT_ID), anyLong())).thenReturn(factories);

        String segment = AiGenServiceImpl.projectResourceSegment(coreGateway, PROJECT_ID);

        assertEquals(30, segment.lines().filter(l -> l.contains("数据工厂：工厂")).count(),
                "listed items must stay within the cap: " + segment);
        assertTrue(segment.contains("其余 15 项"), "truncated tail must be honest: " + segment);
    }

    @Test
    void emptyPoolOrDoorFailureFoldsToEmptySegment() {
        assertEquals("", AiGenServiceImpl.projectResourceSegment(coreGateway, PROJECT_ID),
                "empty pool must keep the legacy prompt bytes");

        when(coreGateway.listProjectDataFactories(eq(PROJECT_ID), anyLong()))
                .thenThrow(new IllegalStateException("door down"));
        assertEquals("", AiGenServiceImpl.projectResourceSegment(coreGateway, PROJECT_ID),
                "door failure must fold to zero injection, never block generation");
    }

    @Test
    void resourceSegmentRidesStage2PromptOncePerTask() {
        stubFullPool(coreGateway);

        regenerateCase();

        assertTrue(!scriptPrompts.isEmpty(), "stage-2 pass never reached the LLM door");
        String prompt = scriptPrompts.get(0);
        assertTrue(prompt.contains("订单造数"), "pool segment not spliced into stage-2 prompt");
        assertTrue(prompt.contains("交易接口集"), "pool segment not spliced into stage-2 prompt");
        verify(coreGateway, times(1)).listProjectEnvs(PROJECT_ID);
        verify(coreGateway, times(1)).listProjectDataFactories(eq(PROJECT_ID), anyLong());
        verify(coreGateway, times(1)).listProjectApiCollections(eq(PROJECT_ID), anyLong());
    }

    @Test
    void caseLevelFewshotRidesStage2PromptOncePerTask() {
        when(fewshotBridge.fewshotSegment(eq(TASK_ID), eq(PROJECT_ID), any(), any()))
                .thenReturn("\n风格样例段（测试桩）：\n样例A\n");

        regenerateCase();

        assertTrue(!scriptPrompts.isEmpty(), "stage-2 pass never reached the LLM door");
        assertTrue(scriptPrompts.get(0).contains("风格样例段（测试桩）"),
                "few-shot segment not spliced into stage-2 prompt");
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(fewshotBridge, times(1))
                .fewshotSegment(eq(TASK_ID), eq(PROJECT_ID), query.capture(), any());
        assertTrue(query.getValue().contains("下单流程"),
                "stage-2 few-shot must query with the case body, not the raw requirement");
    }

    @Test
    void emptyFewshotSegmentKeepsLegacyBytes() {
        when(fewshotBridge.fewshotSegment(eq(TASK_ID), eq(PROJECT_ID), any(), any()))
                .thenReturn("");
        stubFullPool(coreGateway);

        regenerateCase();

        assertTrue(scriptPrompts.get(0).contains("订单造数"),
                "pool segment must still ride when the style segment is empty");
    }
}
