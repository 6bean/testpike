package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.domain.component.ConfidenceMeasurer;
import com.sixbean.testpike.aiservice.gen.domain.component.FidelityGate;
import com.sixbean.testpike.aiservice.gen.domain.component.GenStructuredOutput;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Honest-mark confidence clamp suite : when the draft
 * admits steps without truth backing — an evidenceless URL / locator /
 * can never claim the top level while the marking rule is on. The rule
 * reads the SAME dual keys as the audit and prompt sites (master
 * {@code ai.gen.fidelity-gate.enabled} + {@code ai.gen.no-truth-honest-mark
 * .enabled}, factory on); both sides off keep the legacy byte-for-byte
 * shape (R32). A mark on a step without any claim is not a signal — the
 * honest absence must not be punished.
 * <p>L2 stance: mock CoreGateway/Llm chain (infrastructure only); parser,
 * FidelityGate and ConfidenceMeasurer are the real components.
 */
class AiGenServiceImplHonestMarkClampWiringTest {

    private static final long TASK_ID = 6841L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long REQUIREMENT_ID = 7L;
    private static final long NEW_CASE_ID = 68411L;

    private static final String SIGNALS_KEY = "ai.gen.confidence-signals.enabled";

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();
    private final LlmFallbackChain llmChain = GenWiringHarness.mockLlmChain();
    private final ProjectLlmRouter llmRouter = GenWiringHarness.mockLlmRouter();

    private final AiGenServiceImpl service = GenWiringHarness.assemble(
            coreGateway, llmChain, llmRouter, new FidelityGate(), new ConfidenceMeasurer());

    private final ArrayList<String> stage1Prompts = new ArrayList<>();

    /** No digits anywhere: the quantity audit never joins the scene. */
    private static final String REQUIREMENT_TEXT = "用户输入正确账号密码跳转首页";
    private static final String ACCEPTANCE = "跳转首页";

    /** Self-reported 1 with one honest-marked URL step (claims backed by mark). */
    private static final String MARKED_DRAFT = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"打开 http://shop.test/login（需人工精化）"},
                      {"stepNo":2,"description":"断言跳转首页","isAssert":1}],
             "expectedResult":"跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    /** Same shape, but the mark sits on a step without any evidenceless claim. */
    private static final String STRAY_MARK_DRAFT = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"打开登录页（需人工精化）"},
                      {"stepNo":2,"description":"断言跳转首页","isAssert":1}],
             "expectedResult":"跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    private void stubRouting(Map<String, String> configRows) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "honest", "zh-CN", 0));
        when(coreGateway.getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "登录", ACCEPTANCE));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-honest-68411"));
        when(coreGateway.getSysConfig(any())).thenAnswer(inv ->
                configRows.get(inv.getArgument(0)));
    }

    private static Map<String, String> baseRows() {
        Map<String, String> rows = new HashMap<>();
        rows.put(GenStructuredOutput.GATE_KEY, "0");
        rows.put(DeliberationLoop.GOVERNANCE_KEY, null);
        return rows;
    }

    @SuppressWarnings("unchecked")
    private int persistedConfidence() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(coreGateway).createCase(eq(OPERATOR_ID), captor.capture());
        return (int) captor.getValue().get("confidence");
    }

    private AiGenMessage message() {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1,
                REQUIREMENT_TEXT, REQUIREMENT_ID, null);
    }

    @Test
    void honestMarkedClaimStepClampsTopSelfReportToLevel2() {
        Map<String, String> rows = baseRows();
        rows.put(SIGNALS_KEY, "false"); // isolate the clamp from the measured composite
        stubRouting(rows);
        GenWiringHarness.stubStage1Drafts(llmChain, PROJECT_ID, stage1Prompts, MARKED_DRAFT);

        service.generate(message());

        assertEquals(2, persistedConfidence(),
                "a draft that admits an evidenceless step must not persist level 1");
    }

    @Test
    void ruleOffKeepsLegacyShapeByteForByte() {
        Map<String, String> rows = baseRows();
        rows.put(SIGNALS_KEY, "false");
        rows.put(AiGenServiceImpl.NO_TRUTH_HONEST_MARK_KEY, "false");
        stubRouting(rows);
        GenWiringHarness.stubStage1Drafts(llmChain, PROJECT_ID, stage1Prompts, MARKED_DRAFT);

        service.generate(message());

        assertEquals(1, persistedConfidence(),
                "rule off: no clamp may fire, the self-report persists untouched (R32)");
    }

    @Test
    void strayMarkWithoutClaimIsNotASignal() {
        Map<String, String> rows = baseRows();
        rows.put(SIGNALS_KEY, "false");
        stubRouting(rows);
        GenWiringHarness.stubStage1Drafts(llmChain, PROJECT_ID, stage1Prompts, STRAY_MARK_DRAFT);

        service.generate(message());

        assertEquals(1, persistedConfidence(),
                "honest absence on a claim-free step must not be punished");
    }

    @Test
    void clampAlsoAppliesAfterTheMeasuredComposite() {
        // Signals on: faithful + covered + anchored measures the top level from
        // the real signals; the marked draft must still land at 2, never 1.
        stubRouting(baseRows());
        GenWiringHarness.stubStage1Drafts(llmChain, PROJECT_ID, stage1Prompts, MARKED_DRAFT);

        service.generate(message());

        assertEquals(2, persistedConfidence(),
                "measured level 1 rides the same clamp: honest marks cap the top level");
    }

    // ── Stage-2 consumption wiring (the real-chain shape) ─────────────────
    // The marking clause rides the stage-2 prompt builder, so a live run
    // carries the honest mark on the SCRIPT draft only — the stage-1 case
    // skeleton never holds one. These suites pin the clamp to the draft that
    // was actually saved, through the field-level case door.

    /** Stage-1 skeleton without any mark: the top self-report survives. */
    private static final String CLEAN_STAGE1_DRAFT = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"打开登录页"},
                      { "stepNo":2,"description":"断言跳转首页","isAssert":1}],
             "expectedResult":"跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    /** Stage-2 script whose goto step admits the unbacked URL with the mark. */
    private static final String STAGE2_MARKED_SCRIPT = """
            {"version":"v2","caseId":"TC-honest-68411","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "steps":[{"action":"goto","description":"打开 http://shop.test/login（需人工精化）",
                       "target":{"url":"http://shop.test/login","chain":["url"]}},
                      {"action":"expect","description":"断言进入首页",
                       "target":{"css":"#home","chain":["css"]}}]}""";

    /** Stage-2 script without URL / text / expected-value claims: no signal. */
    private static final String STAGE2_NO_CLAIM_SCRIPT = """
            {"version":"v2","caseId":"TC-honest-68411","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "steps":[{"action":"click","description":"点击查询按钮",
                       "target":{"css":"#query","chain":["css"]}}]}""";

    @Test
    void stage2MarkedScriptRaisesTheSavedCaseToLevel2() {
        Map<String, String> rows = baseRows();
        rows.put(SIGNALS_KEY, "false"); // isolate the clamp from the measured composite
        stubRouting(rows);
        GenWiringHarness.stubStageFlow(llmChain, PROJECT_ID, stage1Prompts,
                new String[] {CLEAN_STAGE1_DRAFT}, STAGE2_MARKED_SCRIPT);

        service.generate(message());

        verify(coreGateway).clampCaseConfidence(NEW_CASE_ID, 2, OPERATOR_ID);
    }

    @Test
    void stage2WithoutClaimsNeverTouchesTheClampDoor() {
        Map<String, String> rows = baseRows();
        rows.put(SIGNALS_KEY, "false");
        stubRouting(rows);
        GenWiringHarness.stubStageFlow(llmChain, PROJECT_ID, stage1Prompts,
                new String[] {CLEAN_STAGE1_DRAFT}, STAGE2_NO_CLAIM_SCRIPT);

        service.generate(message());

        verify(coreGateway, never()).clampCaseConfidence(anyLong(), anyInt(), anyLong());
        assertEquals(1, persistedConfidence(), "honest absence must not be punished");
    }

    @Test
    void ruleOffKeepsStage2ShapeByteForByteNoDoor() {
        Map<String, String> rows = baseRows();
        rows.put(SIGNALS_KEY, "false");
        rows.put(AiGenServiceImpl.NO_TRUTH_HONEST_MARK_KEY, "false");
        stubRouting(rows);
        GenWiringHarness.stubStageFlow(llmChain, PROJECT_ID, stage1Prompts,
                new String[] {CLEAN_STAGE1_DRAFT}, STAGE2_MARKED_SCRIPT);

        service.generate(message());

        verify(coreGateway, never()).clampCaseConfidence(anyLong(), anyInt(), anyLong());
        assertEquals(1, persistedConfidence(),
                "rule off: the marked script persists the self-report untouched (R32)");
    }

    @Test
    void caseAlreadyBelowTopLevelIsNeverRewritten() {
        Map<String, String> rows = baseRows();
        rows.put(SIGNALS_KEY, "false");
        stubRouting(rows);
        GenWiringHarness.stubStageFlow(llmChain, PROJECT_ID, stage1Prompts,
                new String[] {CLEAN_STAGE1_DRAFT.replace("\"confidence\":1", "\"confidence\":2")},
                STAGE2_MARKED_SCRIPT);

        service.generate(message());

        verify(coreGateway, never()).clampCaseConfidence(anyLong(), anyInt(), anyLong());
    }

    @Test
    void clampDoorRejectionNeverBlocksGeneration() {
        Map<String, String> rows = baseRows();
        rows.put(SIGNALS_KEY, "false");
        stubRouting(rows);
        doThrow(new ServiceException("optimistic lock rejected"))
                .when(coreGateway).clampCaseConfidence(anyLong(), anyInt(), anyLong());
        GenWiringHarness.stubStageFlow(llmChain, PROJECT_ID, stage1Prompts,
                new String[] {CLEAN_STAGE1_DRAFT}, STAGE2_MARKED_SCRIPT);

        assertDoesNotThrow(() -> service.generate(message()));

        verify(coreGateway).markDone(TASK_ID, NEW_CASE_ID);
    }
}
