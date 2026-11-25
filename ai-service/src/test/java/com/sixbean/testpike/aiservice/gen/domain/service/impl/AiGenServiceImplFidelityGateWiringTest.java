package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.domain.component.FidelityGate;
import com.sixbean.testpike.aiservice.gen.domain.component.GenStructuredOutput;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop;
import com.sixbean.testpike.aiservice.testsupport.LogbackSink;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Faithfulness-checker wiring suite : the stage1 deliberation
 * ring's verifier must carry the FidelityGate verdict on the existing evidence
 * blocks — a quantity claim the source does not literally carry (the "5 s"
 * tampered from "15 s" shape) becomes a named regeneration feedback, while the
 * loose substring rule of the shared anchor gate alone would let it through.
 * Both K8 keys read per call: the factory-on master switch (absent row = on)
 * and the regeneration budget (default 1, range 0..2); off or budget 0 keeps
 * the legacy byte shape with an honest WARN instead of silence.
 * <p>L2 stance: mock CoreGateway/LlmFallbackChain (infrastructure only);
 * parser, self-check, ring, FidelityGate and the number-provenance kernel are
 * the real components.
 */
class AiGenServiceImplFidelityGateWiringTest {

    private static final long TASK_ID = 541L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 5411L;

    /** Anchor marker of the dispute line (shared with the L3 window scripts). */
    private static final String DISPUTE_MARK = "数值断言无原文依据";
    /** Anchor marker of the honest non-blocking notice when the budget is spent. */
    private static final String NOTICE_MARK = "Fidelity gate round budget exhausted";

    private static final String FIDELITY_GATE_KEY = "ai.gen.fidelity-gate.enabled";
    private static final String FIDELITY_ROUNDS_KEY = "ai.gen.fidelity-recheck-rounds";

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();
    private final LlmFallbackChain llmChain = GenWiringHarness.mockLlmChain();
    private final ProjectLlmRouter llmRouter = GenWiringHarness.mockLlmRouter();

    private final AiGenServiceImpl service = GenWiringHarness.assemble(
            coreGateway, llmChain, llmRouter, new FidelityGate(), null);

    private final List<String> stage1Prompts = new ArrayList<>();

    /** Requirement carries the only audited figure: 15 (never a bare 5). */
    private static final String REQUIREMENT_TEXT = "用户输入正确账号密码，15秒内跳转首页";

    /** Draft claiming 15 s — every quantity the source literally carries. */
    private static final String STAGE1_FAITHFUL = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"断言首页","isAssert":1}],
             "expectedResult":"15秒内跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    /** Draft tampered to 5 s: passes the loose contains rule, fails the gate. */
    private static final String STAGE1_TAMPERED = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"断言首页","isAssert":1}],
             "expectedResult":"5秒内跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    private void stubRouting(Map<String, String> configRows) {
        Map<String, String> rows = new HashMap<>(configRows);
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "fidelity", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-fidelity-5411"));
        when(coreGateway.getSysConfig(any())).thenAnswer(inv ->
                rows.get(inv.getArgument(0)));
    }

    /** Valid action JSON for the post-stage1 script pass lives in the harness. */
    private void stubStage1Drafts(String... drafts) {
        GenWiringHarness.stubStage1Drafts(llmChain, PROJECT_ID, stage1Prompts, drafts);
    }

    private AiGenMessage message() {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, REQUIREMENT_TEXT, null, null);
    }

    /**
 * Baseline rows: structured gate off (legacy shape, no response_format noise).
 * The honest-mark rule is switched off here on purpose: this class pins the
 * revision-round wiring that predates , so it exercises the documented
 * OFF state (zero drift, legacy shape). The ON-state detection and prompt
 * clause are covered by FidelityGateTest and AiGenServiceImplTest fixtures.
 */
    private static Map<String, String> baseRows() {
        Map<String, String> rows = new HashMap<>();
        rows.put(GenStructuredOutput.GATE_KEY, "0");
        rows.put(DeliberationLoop.GOVERNANCE_KEY, null);
        rows.put(AiGenServiceImpl.NO_TRUTH_HONEST_MARK_KEY, "false");
        return rows;
    }

    @Test
    void tamperedQuantityFeedsOneNamedRegenerationRound() {
        stubRouting(baseRows());
        stubStage1Drafts(STAGE1_TAMPERED);
        service.generate(message());
        assertEquals(2, stage1Prompts.size(),
                "budget 1: one revise round driven by the fidelity dispute");
        assertTrue(stage1Prompts.get(0).indexOf(DISPUTE_MARK) < 0,
                "the proposal pass carries no feedback yet");
        assertTrue(stage1Prompts.get(1).contains(DISPUTE_MARK),
                "revision names the unsupported figure: " + stage1Prompts.get(1));
        assertTrue(stage1Prompts.get(1).contains("5"),
                "the dispute quotes the tampered digit run");
    }

    @Test
    void faithfulDraftConvergesWithoutExtraRound() {
        stubRouting(baseRows());
        stubStage1Drafts(STAGE1_FAITHFUL);
        service.generate(message());
        assertEquals(1, stage1Prompts.size(),
                "source-backed claims must not burn a revision");
    }

    @Test
    void switchOffKeepsLegacyShapeAndStaysSilent() {
        Map<String, String> rows = baseRows();
        rows.put(FIDELITY_GATE_KEY, "false");
        stubRouting(rows);
        stubStage1Drafts(STAGE1_TAMPERED);
        try (LogbackSink sink = LogbackSink.attach(AiGenServiceImpl.class)) {
            service.generate(message());
            assertEquals(1, stage1Prompts.size(),
                    "gate off: the verifier must not add fidelity disputes");
            assertEquals(0, sink.warnsContaining(NOTICE_MARK).size(),
                    "gate off leaves no residue to announce: " + sink.warns());
        }
    }

    @Test
    void zeroRoundsAuditsWithoutBlockingButAnnouncesHonestly() {
        Map<String, String> rows = baseRows();
        rows.put(FIDELITY_ROUNDS_KEY, "0");
        stubRouting(rows);
        stubStage1Drafts(STAGE1_TAMPERED);
        try (LogbackSink sink = LogbackSink.attach(AiGenServiceImpl.class)) {
            service.generate(message());
            assertEquals(1, stage1Prompts.size(),
                    "budget 0: named disputes must never drive a revision");
            assertTrue(sink.warnsContaining(NOTICE_MARK).size() >= 1,
                    "audited-but-not-fed-back disputes are surfaced, never silent: "
                            + sink.warns());
        }
    }
}
