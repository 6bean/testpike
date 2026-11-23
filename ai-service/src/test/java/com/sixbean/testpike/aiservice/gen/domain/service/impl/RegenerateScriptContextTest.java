package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.api.dto.GeneratedCase;
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
 * Script-regeneration context fidelity (taskType=2 path): the stage-2 prompt
 * must carry the stored case content — precondition, step lines and the
 * expected result — instead of the title alone, and the end type must come
 * from the stored row rather than the hard-coded legacy default.
 * <p>L2 posture: CoreGateway / LlmFallbackChain / ProjectLlmRouter are
 * infrastructure mocks (GenWiringHarness single source); every judgement
 * component stays the real one. The live chain contract is covered by the
 * L3 window, never replaced by this suite.
 */
class RegenerateScriptContextTest {

    private static final long TASK_ID = 159L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long CASE_ID = 5600L;

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();
    private final LlmFallbackChain llmChain = GenWiringHarness.mockLlmChain();
    private final ProjectLlmRouter llmRouter = GenWiringHarness.mockLlmRouter();

    private final List<String> scriptPrompts = new ArrayList<>();

    /** Stored-case row in the exact core CaseInfo JSON shape (getCase door). */
    private static Map<String, Object> caseRow() {
        return Map.of(
                "id", CASE_ID,
                "caseNo", "TC-DEMO-1",
                "title", "下单流程",
                "precondition", "用户已登录并进入商品详情页",
                "expectedResult", "订单创建成功并跳转订单列表",
                "priority", 1,
                "confidence", 2,
                "endType", "mobile",
                "steps", List.of(
                        Map.of("stepNo", 1, "description", "点击立即购买按钮", "isAssert", 0),
                        Map.of("stepNo", 2, "description", "核对订单号已展示", "isAssert", 1)));
    }

    @BeforeEach
    void wireInfrastructure() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        // structured-output gate off: prompt capture stays on the legacy shape
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

    @Test
    void regenerateStage2PromptCarriesStoredCaseContent() {
        service().generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                2, "", null, CASE_ID));

        assertTrue(!scriptPrompts.isEmpty(), "stage-2 pass never reached the LLM door");
        String prompt = scriptPrompts.get(0);
        assertTrue(prompt.contains("前置条件：用户已登录并进入商品详情页"),
                "stored precondition missing from the stage-2 prompt: " + prompt);
        assertTrue(prompt.contains("1. 点击立即购买按钮"),
                "stored step line 1 missing from the stage-2 prompt: " + prompt);
        assertTrue(prompt.contains("2. 核对订单号已展示（断言）"),
                "stored assertion step line missing from the stage-2 prompt: " + prompt);
        assertTrue(prompt.contains("预期结果：订单创建成功并跳转订单列表"),
                "stored expected result missing from the stage-2 prompt: " + prompt);
    }

    @Test
    void mappingKeepsStoredEndTypeInsteadOfHardcodedWeb() {
        GeneratedCase mapped = AiGenServiceImpl.generatedFromCaseInfo(caseRow());

        assertEquals("mobile", mapped.endType(),
                "stored end type must ride the DTO instead of the legacy web default");
        assertEquals(2, mapped.steps().size());
        assertEquals(1, mapped.steps().get(1).isAssert());
        assertEquals("用户已登录并进入商品详情页", mapped.precondition());
        assertEquals("订单创建成功并跳转订单列表", mapped.expectedResult());
    }

    @Test
    void mappingFallsBackToLegacyShapeWhenRowMissingOrPartial() {
        GeneratedCase missing = AiGenServiceImpl.generatedFromCaseInfo(null);
        assertEquals("用例", missing.title());
        assertEquals("web", missing.endType());
        assertTrue(missing.steps().isEmpty());

        GeneratedCase partial = AiGenServiceImpl.generatedFromCaseInfo(
                Map.of("title", "半行用例", "steps", List.of(Map.of("description", "  "))));
        assertEquals("半行用例", partial.title());
        assertEquals("web", partial.endType(), "absent end type keeps the web fallback");
        assertTrue(partial.steps().isEmpty(), "blank step descriptions are not injected");
    }
}
