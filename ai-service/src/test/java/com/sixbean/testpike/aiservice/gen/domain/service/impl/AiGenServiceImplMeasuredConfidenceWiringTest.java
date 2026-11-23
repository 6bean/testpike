package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.domain.component.ConfidenceMeasurer;
import com.sixbean.testpike.aiservice.gen.domain.component.FidelityGate;
import com.sixbean.testpike.aiservice.gen.domain.component.GenStructuredOutput;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop;
import com.sixbean.testpike.aiservice.testsupport.LogbackSink;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Measured-confidence wiring suite : after the stage1 ring
 * converges, the persisted confidence is replaced by the measured composite —
 * fidelity ratio from the gate run, acceptance-criteria coverage ratio
 * from the shared CoverageChecker, source coverage from EvidenceAnchorGate —
 * through the hot switch {@code ai.gen.confidence-signals.enabled} (factory
 * on). Off keeps the stored value byte-for-byte the model self-report.
 * Measured level 3 under the on switch auto-limits the depth: the stage2
 * script pass is skipped with a WARN and the notification body says honestly
 * that no script was produced (regeneration path untouched).
 * <p>L2 stance: mock CoreGateway/LlmFallbackChain (infrastructure only);
 * parser, checkers, ring and both gates are the real components.
 */
class AiGenServiceImplMeasuredConfidenceWiringTest {

    private static final long TASK_ID = 5413L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long REQUIREMENT_ID = 7L;
    private static final long NEW_CASE_ID = 54131L;

    private static final String SIGNALS_KEY = "ai.gen.confidence-signals.enabled";
    /** Anchor marker of the auto depth-limit notice. */
    private static final String DEPTH_LIMIT_MARK = "低置信自动限深";

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();
    private final LlmFallbackChain llmChain = GenWiringHarness.mockLlmChain();
    private final ProjectLlmRouter llmRouter = GenWiringHarness.mockLlmRouter();

    private final AiGenServiceImpl service = GenWiringHarness.assemble(
            coreGateway, llmChain, llmRouter, new FidelityGate(), new ConfidenceMeasurer());

    private final List<String> stage1Prompts = new ArrayList<>();

    /** Requirement carries the only audited figure: 15 (never a bare 5). */
    private static final String REQUIREMENT_TEXT = "用户输入正确账号密码，15秒内跳转首页";
    /** One acceptance criterion; the faithful draft covers it verbatim. */
    private static final String ACCEPTANCE = "跳转首页";

    /** Draft claiming 15 s — faithful, covered, no assertive wording to anchor. */
    private static final String STAGE1_FAITHFUL_COVERED = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"断言跳转首页","isAssert":1}],
             "expectedResult":"15秒内跳转首页","priority":0,"confidence":3,"endType":"web"}""";

    /** Draft tampered to 5 s, missing the criterion, assertive wording unanchored. */
    private static final String STAGE1_TAMPERED_UNCOVERED = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"总计5秒内完成","isAssert":1}],
             "expectedResult":"总计5秒内跳转登录页","priority":0,"confidence":1,"endType":"web"}""";

    private void stubRouting(Map<String, String> configRows) {
        Map<String, String> rows = new HashMap<>(configRows);
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "confidence", "zh-CN", 0));
        when(coreGateway.getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "登录", ACCEPTANCE));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-confidence-54131"));
        when(coreGateway.getSysConfig(any())).thenAnswer(inv ->
                rows.get(inv.getArgument(0)));
    }

    private void stubStage1Drafts(String... drafts) {
        GenWiringHarness.stubStage1Drafts(llmChain, PROJECT_ID, stage1Prompts, drafts);
    }

    private AiGenMessage message() {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1,
                REQUIREMENT_TEXT, REQUIREMENT_ID, null);
    }

    /** Baseline rows: structured gate off (legacy shape), signals key absent = on. */
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

    private String notificationBody() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).createNotification(eq(OPERATOR_ID), eq(PROJECT_ID),
                eq("AI_GEN_DONE"), any(), captor.capture(), any());
        return captor.getValue();
    }

    @Test
    void lowMeasuredConfidencePersistsLevel3AndSkipsStage2() {
        stubRouting(baseRows());
        stubStage1Drafts(STAGE1_TAMPERED_UNCOVERED);
        try (LogbackSink sink = LogbackSink.attach(AiGenServiceImpl.class)) {
            service.generate(message());
            assertEquals(3, persistedConfidence(),
                    "all three signals low (tampered figure + uncovered criterion + "
                            + "unanchored assertive wording) must persist level 3 "
                            + "instead of the self-reported 1");
            assertTrue(sink.warnsContaining(DEPTH_LIMIT_MARK).size() >= 1,
                    "the auto depth limit must be announced, never silent: " + sink.warns());
            assertTrue(notificationBody().contains("脚本未生成"),
                    "the notification says honestly that no script was produced: "
                            + notificationBody());
            // stage1 proposal + one fidelity-driven revision = exactly 2 model
            // passes: the stage2 script pass must never be reached.
            assertEquals(2, stage1Prompts.size(),
                    "fidelity dispute drives one revision round, then the ring settles");
            verify(llmChain, times(2)).chatHandle(any(), eq(PROJECT_ID),
                    any(LlmFallbackNotice.class));
        }
    }

    @Test
    void highMeasuredConfidencePersistsLevel1AndStillGeneratesScript() {
        stubRouting(baseRows());
        stubStage1Drafts(STAGE1_FAITHFUL_COVERED);
        service.generate(message());
        assertEquals(1, persistedConfidence(),
                "faithful + covered measures high regardless of the self-reported 3");
        assertTrue(notificationBody().contains("已生成用例并生成脚本"),
                "normal flow keeps the script-produced wording: " + notificationBody());
        // stage1 once + stage2 once.
        verify(llmChain, times(2)).chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class));
    }

    @Test
    void switchOffKeepsSelfReportAndLeavesFlowUntouched() {
        Map<String, String> rows = baseRows();
        rows.put(SIGNALS_KEY, "false");
        stubRouting(rows);
        stubStage1Drafts(STAGE1_TAMPERED_UNCOVERED);
        try (LogbackSink sink = LogbackSink.attach(AiGenServiceImpl.class)) {
            service.generate(message());
            assertEquals(1, persistedConfidence(),
                    "off: the stored value stays the model self-report");
            assertEquals(0, sink.warnsContaining(DEPTH_LIMIT_MARK).size(),
                    "off never limits the depth: " + sink.warns());
            assertTrue(notificationBody().contains("已生成用例并生成脚本"),
                    "off keeps the legacy completion notice: " + notificationBody());
        }
    }
}
