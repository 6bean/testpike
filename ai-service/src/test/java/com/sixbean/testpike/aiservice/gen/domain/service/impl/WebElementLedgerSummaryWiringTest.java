package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.WebElementLedgerSummaryBridge;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WE batch piece C : wiring pin for the page-element summary bridge
 * into the stage-2 (steps-gen) prompt. The bridge component itself is pinned
 * in WebElementLedgerSummaryBridgeTest; this suite pins the SERVICE seam:
 * ① confirmed rows ride the built-in stage-2 prompt tail (title anchor +
 * row lines + reference-first instruction with the {{ELEM:…}} shape);
 * ② gate off = the summary door is never called and the prompt keeps the
 * legacy bytes;
 * ③ empty ledger / door failure = fail-soft, generation never blocked;
 * ④ the summary is computed ONCE per task and reused by every deliberation
 * ring round (AP-6 stable bytes, inspectSegment discipline — a per-round
 * re-read would push the core door past its once-per-task budget).
 * <p>L2 posture: mocked CoreGateway/LlmFallbackChain = infrastructure
 * isolation; the bridge, parser and validators stay the real components.
 * The live-chain pairing belongs to the L3 window of the close package.
 */
class WebElementLedgerSummaryWiringTest {

    private static final long TASK_ID = 98L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long CASE_ID = 981L;
    private static final String ANCHOR = WebElementLedgerSummaryBridge.SEGMENT_ANCHOR;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final WebElementLedgerSummaryBridge bridge =
            new WebElementLedgerSummaryBridge(coreGateway, new ObjectMapper());

    private final AiGenServiceImpl service = GenWiringHarness.assemble(
            coreGateway, llmChain, llmRouter, null, null, null, null, bridge);

    private final List<String> stage2UserMessages = new ArrayList<>();

    private static final String STAGE2_JSON = "{\"version\":\"v2\",\"caseId\":\"TC-REG-001\",\"endType\":\"web\","
            + "\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\"{{ENV:URL}}/login\"}}],"
            + "\"steps\":[{\"action\":\"input\",\"target\":{\"css\":\"#username\",\"chain\":[\"css\"]},"
            + "\"value\":\"u\"},{\"action\":\"expect\",\"target\":{\"text\":\"首页\"}}],"
            + "\"env\":{\"ENV\":\"dev\",\"PACK\":\"generic\",\"DATA\":\"default\"}}";

    private void stubBase() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 0, null, null, null));
        when(coreGateway.getCase(CASE_ID)).thenReturn(Map.of("title", "登录用例"));
    }

    private void stubLlm(String... stage2Outputs) {
        int[] idx = {0};
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String content = stage2Outputs.length == 0 ? STAGE2_JSON
                            : stage2Outputs[Math.min(idx[0]++, stage2Outputs.length - 1)];
                    if (!r.messages().get(0).content().contains("STAGE1_CASE_JSON")) {
                        stage2UserMessages.add(r.messages().get(1).content());
                    }
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "m", 100, 50, 20L),
                            GenWiringHarness.ENDPOINT);
                });
    }

    private static List<Map<String, Object>> confirmedRows() {
        return List.of(Map.of(
                "name", "登录按钮",
                "pageKey", "login",
                "anchors", List.of(Map.of("type", "css", "value", "#login-btn"),
                        Map.of("type", "text", "value", "登录"))));
    }

    private void runGenerate() {
        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 2,
                "重生成登录脚本", null, CASE_ID));
    }

    @Test
    void confirmedRowsRideStage2PromptTail() {
        stubBase();
        stubLlm();
        when(coreGateway.getLedgerPromptSummary(eq(PROJECT_ID), isNull(), anyInt()))
                .thenReturn(confirmedRows());

        runGenerate();

        String prompt = stage2UserMessages.get(0);
        assertTrue(prompt.contains(ANCHOR), "摘要标题锚未注入 stage2 提示:\n" + prompt);
        assertTrue(prompt.contains("「登录按钮」") && prompt.contains("主锚 css=#login-btn"),
                "已确认元素行未注入: " + prompt);
        assertTrue(prompt.contains("{{ELEM:"), "引用优先规则未注入: " + prompt);
        verify(coreGateway).saveScript(eq(CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
    }

    @Test
    void gateOffSkipsDoorAndKeepsLegacyBytes() {
        stubBase();
        stubLlm();
        when(coreGateway.getSysConfig(WebElementLedgerSummaryBridge.KEY_ENABLED))
                .thenReturn("false");

        runGenerate();

        verify(coreGateway, never()).getLedgerPromptSummary(eq(PROJECT_ID), isNull(), anyInt());
        assertFalse(stage2UserMessages.get(0).contains(ANCHOR), "闸关不得注入摘要段");
        verify(coreGateway).saveScript(eq(CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
    }

    @Test
    void emptyLedgerKeepsLegacyBytes() {
        stubBase();
        stubLlm();
        when(coreGateway.getLedgerPromptSummary(eq(PROJECT_ID), isNull(), anyInt()))
                .thenReturn(List.of());

        runGenerate();

        assertFalse(stage2UserMessages.get(0).contains(ANCHOR), "空账不得注入摘要段");
        verify(coreGateway).saveScript(eq(CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
    }

    @Test
    void doorFailureIsFailSoftAndGenerationCompletes() {
        stubBase();
        stubLlm();
        when(coreGateway.getLedgerPromptSummary(eq(PROJECT_ID), isNull(), anyInt()))
                .thenThrow(new IllegalStateException("core prompt-summary door unreachable"));

        runGenerate();

        assertFalse(stage2UserMessages.get(0).contains(ANCHOR), "读失败不得注入摘要段");
        verify(coreGateway).saveScript(eq(CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
    }

    @Test
    void ringRoundsReuseOneSummaryRead() {
        stubBase();
        stubLlm("这不是合法动作 JSON", STAGE2_JSON);
        when(coreGateway.getLedgerPromptSummary(eq(PROJECT_ID), isNull(), anyInt()))
                .thenReturn(confirmedRows());

        runGenerate();

        assertTrue(stage2UserMessages.size() >= 2, "应出现修订轮: " + stage2UserMessages.size());
        assertTrue(stage2UserMessages.get(1).contains(ANCHOR), "修订轮必须复用同一摘要段");
        // Once-per-task guard: a per-round re-read would push the door past 1.
        verify(coreGateway, times(1)).getLedgerPromptSummary(eq(PROJECT_ID), isNull(), anyInt());
    }
}
