package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.domain.component.FidelityGate;
import com.sixbean.testpike.aiservice.gen.domain.component.GenStructuredOutput;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Stage-2 prior-failure fold-back :
 * the case's recent failed repair notes must ride the script-regeneration
 * prompt so the model stops re-producing steps the repair loop already
 * rejected. Passing / skipped-outside-the-gate ledger rows fold to nothing;
 * an empty history keeps the first attempt byte-identical to the legacy
 * prompt. L2 posture: infrastructure mocks via GenWiringHarness single
 * source; every judgement component stays the real one (live-chain contract
 * belongs to the L3 window, never replaced here).
 */
class PriorRepairFeedbackTest {

    private static final long TASK_ID = 161L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long CASE_ID = 5602L;

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();
    private final LlmFallbackChain llmChain = GenWiringHarness.mockLlmChain();
    private final ProjectLlmRouter llmRouter = GenWiringHarness.mockLlmRouter();

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
        when(coreGateway.getCase(CASE_ID)).thenReturn(caseRow());
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    scriptPrompts.add(r.messages().get(1).content());
                    return GenWiringHarness.handle(GenWiringHarness.STAGE2_CLEAN);
                });
    }

    private AiGenServiceImpl service() {
        return GenWiringHarness.assemble(coreGateway, llmChain, llmRouter,
                new FidelityGate(), null);
    }

    private static Map<String, Object> repairRow(int outcome, String failNote) {
        return Map.of("outcome", outcome, "failNote", failNote);
    }

    @Test
    void failedNotesFoldNewestFirstWithinCap() {
        List<Map<String, Object>> rows = List.of(
                repairRow(2, "登录步定位失败：按钮未出现"),
                repairRow(1, "通过行不应入段"),
                repairRow(2, "断言超时：金额未刷新"),
                repairRow(3, "闸外跳过不应入段"));

        assertEquals(List.of("登录步定位失败：按钮未出现", "断言超时：金额未刷新"),
                AiGenServiceImpl.priorRepairFailures(rows, 2));
        assertEquals(List.of("登录步定位失败：按钮未出现"),
                AiGenServiceImpl.priorRepairFailures(rows, 1));
    }

    @Test
    void blankMissingOrAbsentHistoryFoldsToNothing() {
        List<Map<String, Object>> rows = List.of(
                Map.of("outcome", 2),
                repairRow(2, "   "),
                Map.of("failNote", "无结局码的行不入段"));

        assertTrue(AiGenServiceImpl.priorRepairFailures(rows, 3).isEmpty());
        assertTrue(AiGenServiceImpl.priorRepairFailures(null, 3).isEmpty());
        assertEquals(3, AiGenServiceImpl.repairFoldCap(null));
        assertEquals(0, AiGenServiceImpl.repairFoldCap("0"));
        assertEquals(9, AiGenServiceImpl.repairFoldCap("99"));
        assertEquals(3, AiGenServiceImpl.repairFoldCap("abc"));
    }

    @Test
    void firstAttemptWithoutHistoryKeepsLegacyPromptBytes() {
        service().generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                2, "", null, CASE_ID));

        assertTrue(!scriptPrompts.isEmpty(), "stage-2 pass never reached the LLM door");
        assertTrue(!scriptPrompts.get(0).contains("既往修复失败"),
                "empty ledger must not inject the fold-back segment");
    }

    @Test
    void regeneratePromptCarriesPriorFailedRepairs() {
        when(coreGateway.listCaseRepairs(CASE_ID, 3)).thenReturn(List.of(
                repairRow(2, "登录步定位失败：按钮未出现"),
                repairRow(1, "通过行不应入段")));

        service().generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                2, "", null, CASE_ID));

        assertTrue(!scriptPrompts.isEmpty(), "stage-2 pass never reached the LLM door");
        String prompt = scriptPrompts.get(0);
        assertTrue(prompt.contains("既往修复失败"),
                "fold-back header missing from the stage-2 prompt: " + prompt);
        assertTrue(prompt.contains("登录步定位失败：按钮未出现"),
                "failed repair note missing from the stage-2 prompt: " + prompt);
        assertTrue(!prompt.contains("通过行不应入段"),
                "passing ledger row must not ride the prompt");
    }
}
