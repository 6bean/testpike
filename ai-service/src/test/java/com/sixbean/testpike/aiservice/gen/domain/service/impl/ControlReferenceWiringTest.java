package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.domain.component.FidelityGate;
import com.sixbean.testpike.aiservice.gen.domain.component.GenStructuredOutput;
import com.sixbean.testpike.aiservice.gen.domain.component.PageInspectBridge;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.DeliberationLoop;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Control-reference wiring suite :
 * when the stage2 page-inspect candidate list is present, a step that quotes a
 * control the candidate probe never saw becomes a named dispute inside the
 * deliberation ring — the revision round gets the ghost name in its feedback,
 * exactly like the quantity shape of the faithfulness checker. No candidate
 * segment (probe off / fail-soft) keeps the legacy single-pass shape: the
 * audit has no existence evidence and must never invent disputes.
 * <p>L2 stance: mocked infrastructure only (CoreGateway / LlmFallbackChain /
 * PageInspectBridge); the ring, validator and FidelityGate are real.
 */
class ControlReferenceWiringTest {

    private static final long TASK_ID = 542L;
    private static final long PROJECT_ID = 4L;
    private static final long OPERATOR_ID = 12L;
    private static final long NEW_CASE_ID = 5421L;

    /** Anchor marker of the control dispute line. */
    private static final String CONTROL_MARK = "控件引用无候选依据";

    private final CoreGateway coreGateway = GenWiringHarness.mockCoreGateway();
    private final LlmFallbackChain llmChain = GenWiringHarness.mockLlmChain();
    private final ProjectLlmRouter llmRouter = GenWiringHarness.mockLlmRouter();
    private final PageInspectBridge inspectBridge = mock(PageInspectBridge.class);

    
    private static final String CANDIDATE_SEGMENT = PageInspectBridge.SEGMENT_ANCHOR + "\n"
            + "- <button> 「登录」 css=#login-btn\n"
            + "- <input> 「用户名」 css=#username\n"
            + "- <a> text=首页\n"
            + "定位要求：steps/setup 中命中上述元素的动作必须原样复用候选 target。\n";

    /** Stage1 case draft — faithful to the requirement, never re-audited here. */
    private static final String STAGE1_CASE = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"断言首页","isAssert":1}],
             "expectedResult":"15秒内跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    /** Script draft quoting a control the candidate probe never saw. */
    private static final String SCRIPT_GHOST_CONTROL = """
            {"version":"v2","caseId":"TC-ctl-5421","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "setup":[{"action":"goto","description":"打开登录页","target":{"url":"{{ENV:BASE}}/login"}}],
             "steps":[
               {"action":"click","description":"点击「立即登录」","target":{"css":"#ghost","chain":["css"]}},
               {"action":"expect","description":"断言跳转","target":{"text":"首页"},"value":"首页"}
             ]}""";

    /** Script draft whose quoted controls are all candidate-backed. */
    private static final String SCRIPT_BACKED = """
            {"version":"v2","caseId":"TC-ctl-5421","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "setup":[{"action":"goto","description":"打开登录页","target":{"url":"{{ENV:BASE}}/login"}}],
             "steps":[
               {"action":"click","description":"点击「登录」","target":{"css":"#login-btn","chain":["css"]}},
               {"action":"expect","description":"断言跳转","target":{"text":"首页"},"value":"首页"}
             ]}""";

    /** Same claims as SCRIPT_GHOST_CONTROL, each step admitting the marking. */
    private static final String SCRIPT_HONEST_MARKED = """
            {"version":"v2","caseId":"TC-ctl-5421","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "setup":[{"action":"goto","description":"打开登录页","target":{"url":"{{ENV:BASE}}/login"}}],
             "steps":[
               {"action":"click","description":"点击「立即登录」（需人工精化）","target":{"css":"#ghost","chain":["css"]}},
               {"action":"expect","description":"断言跳转（需人工精化）","target":{"text":"首页"},"value":"首页"}
             ]}""";

    /** Anchor marker of the honest-mark dispute line . */
    private static final String HONEST_DISPUTE_MARK = "无据步骤含定位/URL/期望值未标";

    private void stubStage1(Map<String, String> configRows, List<String> scriptPrompts,
                            String... scriptDrafts) {
        Map<String, String> rows = new HashMap<>(configRows);
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "ctl", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-ctl-5421"));
        when(coreGateway.getSysConfig(any())).thenAnswer(inv -> rows.get(inv.getArgument(0)));
        when(inspectBridge.enabledFor(PROJECT_ID)).thenReturn(true);
        int[] idx = {0};
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    if (r.messages().get(0).content().contains("STAGE1_CASE_JSON")) {
                        return GenWiringHarness.handle(STAGE1_CASE);
                    }
                    scriptPrompts.add(r.messages().get(1).content());
                    String draft = idx[0] < scriptDrafts.length
                            ? scriptDrafts[idx[0]] : scriptDrafts[scriptDrafts.length - 1];
                    idx[0]++;
                    return GenWiringHarness.handle(draft);
                });
    }

    private AiGenMessage message() {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1,
                "用户输入正确账号密码，15秒内跳转首页", null, null);
    }

    private static Map<String, String> baseRows() {
        Map<String, String> rows = new HashMap<>();
        rows.put(GenStructuredOutput.GATE_KEY, "0");
        rows.put(DeliberationLoop.GOVERNANCE_KEY, null);
        return rows;
    }

    @Test
    void ghostControlQuotationDrivesOneNamedRevisionRound() {
        List<String> scriptPrompts = new ArrayList<>();
        stubStage1(baseRows(), scriptPrompts, SCRIPT_GHOST_CONTROL, SCRIPT_BACKED);
        when(inspectBridge.inspectSegment(PROJECT_ID, TASK_ID)).thenReturn(CANDIDATE_SEGMENT);
        AiGenServiceImpl service = GenWiringHarness.assemble(coreGateway, llmChain, llmRouter,
                new FidelityGate(), null, inspectBridge);

        service.generate(message());

        assertEquals(2, scriptPrompts.size(),
                "budget: one revision round driven by the ghost control dispute");
        assertTrue(scriptPrompts.get(0).indexOf(CONTROL_MARK) < 0,
                "the proposal pass carries no feedback yet");
        assertTrue(scriptPrompts.get(1).contains(CONTROL_MARK)
                        && scriptPrompts.get(1).contains("立即登录"),
                "revision names the control the candidates never showed: "
                        + scriptPrompts.get(1));
    }

    @Test
    void candidateBackedQuotationConvergesWithoutRevision() {
        List<String> scriptPrompts = new ArrayList<>();
        stubStage1(baseRows(), scriptPrompts, SCRIPT_BACKED);
        when(inspectBridge.inspectSegment(PROJECT_ID, TASK_ID)).thenReturn(CANDIDATE_SEGMENT);
        AiGenServiceImpl service = GenWiringHarness.assemble(coreGateway, llmChain, llmRouter,
                new FidelityGate(), null, inspectBridge);

        service.generate(message());

        assertEquals(1, scriptPrompts.size(),
                "candidate-backed wording must not burn a revision");
    }

    @Test
    void absentCandidateSegmentKeepsLegacySinglePassShape() {
        // Probe off / fail-soft empty segment with the honest-mark rule key
        // explicitly OFF: no existence evidence exists, the same wording must
        // pass exactly as before the control family (closed-door leg of the
        // bidirectional fixture — the open-door leg is the next test).
        List<String> scriptPrompts = new ArrayList<>();
        Map<String, String> rows = baseRows();
        rows.put(AiGenServiceImpl.NO_TRUTH_HONEST_MARK_KEY, "false");
        stubStage1(rows, scriptPrompts, SCRIPT_GHOST_CONTROL);
        when(inspectBridge.inspectSegment(PROJECT_ID, TASK_ID)).thenReturn("");
        AiGenServiceImpl service = GenWiringHarness.assemble(coreGateway, llmChain, llmRouter,
                new FidelityGate(), null, inspectBridge);

        service.generate(message());

        assertEquals(1, scriptPrompts.size(),
                "rule key off: no candidate segment = the audit audited nothing");
    }

    @Test
    void absentCandidateSegmentDrivesHonestMarkRevision() {
        // Open-door leg : empty candidate segment + draft quoting
        // controls/text locators with no evidence source is no longer silent —
        // the existing revision round (no second loop component, R40).
        List<String> scriptPrompts = new ArrayList<>();
        stubStage1(baseRows(), scriptPrompts, SCRIPT_GHOST_CONTROL, SCRIPT_HONEST_MARKED);
        when(inspectBridge.inspectSegment(PROJECT_ID, TASK_ID)).thenReturn("");
        AiGenServiceImpl service = GenWiringHarness.assemble(coreGateway, llmChain, llmRouter,
                new FidelityGate(), null, inspectBridge);

        service.generate(message());

        assertEquals(2, scriptPrompts.size(),
                "default-on honest rule: unmarked evidenceless values drive one revision");
        assertTrue(scriptPrompts.get(0).indexOf(HONEST_DISPUTE_MARK) < 0,
                "the proposal pass carries no feedback yet");
        assertTrue(scriptPrompts.get(1).contains(HONEST_DISPUTE_MARK)
                        && scriptPrompts.get(1).contains("立即登录")
                        && scriptPrompts.get(1).contains(FidelityGate.HONEST_MARK),
                "revision names the unmarked claims and the marking fix: "
                        + scriptPrompts.get(1));
    }

    @Test
    void fidelityGateOffDisablesTheControlAuditToo() {
        // reverts to the exact legacy stage2 shape.
        Map<String, String> rows = baseRows();
        rows.put("ai.gen.fidelity-gate.enabled", "false");
        List<String> scriptPrompts = new ArrayList<>();
        stubStage1(rows, scriptPrompts, SCRIPT_GHOST_CONTROL);
        when(inspectBridge.inspectSegment(PROJECT_ID, TASK_ID)).thenReturn(CANDIDATE_SEGMENT);
        AiGenServiceImpl service = GenWiringHarness.assemble(coreGateway, llmChain, llmRouter,
                new FidelityGate(), null, inspectBridge);

        service.generate(message());

        assertEquals(1, scriptPrompts.size(), "gate off: no control disputes");
    }
}
