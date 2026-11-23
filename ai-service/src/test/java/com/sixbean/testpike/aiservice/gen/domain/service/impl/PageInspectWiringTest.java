package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptLimitResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator;
import com.sixbean.testpike.aiservice.gen.domain.component.PageInspectBridge;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExecTaskResultRemote;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class PageInspectWiringTest {

    private static final long TASK_ID = 97L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long CASE_ID = 971L;
    private static final long EXEC_TASK_ID = 9001L;
    private static final String ANCHOR = "目标页可交互元素候选（已验证存在）";
    /** Truth-source row frozen off in this class (see stubBase pin). */
    private static final String TRUTH_KEY = "ai.gen.truth-source.ui-pageinspect";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final PageInspectBridge pageInspectBridge =
            new PageInspectBridge(coreGateway, new ObjectMapper());

    private final AiGenServiceImpl service = new AiGenServiceImpl(
            llmChain, llmRouter, coreGateway, new ObjectMapper(),
            new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
            new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
            new ScriptLimitResolver(coreGateway), null, null, null, null, null, null, null, null,
            pageInspectBridge);

    private final List<String> stage2UserMessages = new ArrayList<>();

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static final String STAGE2_JSON = "{\"version\":\"v2\",\"caseId\":\"TC-REG-001\",\"endType\":\"web\","
            + "\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\"{{ENV:URL}}/login\"}}],"
            + "\"steps\":[{\"action\":\"input\",\"target\":{\"css\":\"#username\",\"chain\":[\"css\"]},"
            + "\"value\":\"u\"},{\"action\":\"expect\",\"target\":{\"text\":\"首页\"}}],"
            + "\"env\":{\"ENV\":\"dev\",\"PACK\":\"generic\",\"DATA\":\"default\"}}";

    /** Legacy opt-in face: truth-source row frozen off, project column decides. */
    private void stubProject(Integer pageInspectEnabled) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 0, null, null,
                        pageInspectEnabled));
        when(coreGateway.getCase(CASE_ID)).thenReturn(Map.of("title", "登录用例"));
    }

    // pin: this class freezes the legacy opt-in shape for the
    // pre-extension cases, so the truth-source row is explicitly off here and
    // every such case reads through the project column only. The factory-ON
    // judgement lives in the two truthGate cases at the bottom (one rule,
    // two stub faces — no second fixture).
    private void stubBase(Integer pageInspectEnabled) {
        stubProject(pageInspectEnabled);
        when(coreGateway.getSysConfig(TRUTH_KEY)).thenReturn("false");
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
                            new LlmChatResult(content, "openai", "m", 100, 50, 20L), endpoint());
                });
    }

    
    private static ExecTaskResultRemote inspectResult(int status, String detail) {
        try {
            String timeline = new ObjectMapper().writeValueAsString(List.of(Map.of(
                    "stepNo", 1, "action", "element-candidates", "status", "ok",
                    "detail", detail)));
            return new ExecTaskResultRemote(EXEC_TASK_ID, PROJECT_ID, 0L, null, status,
                    null, "explore-v1", null, null, null, null, null,
                    timeline, null, null, null, null, null, null, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String candidateDetail() {
        return "{\"candidates\":[{\"tag\":\"input\",\"text\":\"\",\"target\":{\"css\":\"#username\"}},"
                + "{\"tag\":\"button\",\"text\":\"登录\",\"target\":{\"css\":\"#login-btn\"}}],"
                + "\"count\":2,\"truncated\":false}";
    }

    private void stubHappyInspect() {
        when(coreGateway.listProjectEnvs(PROJECT_ID)).thenReturn(List.of(
                new CoreGateway.EnvBrief(9L, 4, "https://prod.example.com/login", "env"),
                new CoreGateway.EnvBrief(5L, 2, "https://shop.example.com/login", "env")));
        when(coreGateway.submitExploreActStandalone(eq(PROJECT_ID), any())).thenReturn(EXEC_TASK_ID);
        when(coreGateway.pollExecTaskResult(EXEC_TASK_ID))
                .thenReturn(inspectResult(3, candidateDetail()));
    }

    private void runGenerate() {
        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 2,
                "重生成登录脚本", null, CASE_ID));
    }

    @Test
    void 开关关零外呼且生成照常落库() {
        stubBase(0);
        stubLlm();

        runGenerate();

        // Probe door stays shut; the environment LIST read is no longer
        // probe-exclusive — the project resource-pool brief of the script
        // prompt reads it once per task
        // by design, so asserting "never" here would audit the wrong door.
        verify(coreGateway, never()).submitExploreActStandalone(anyLong(), any());
        verify(coreGateway).saveScript(eq(CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
        assertFalse(stage2UserMessages.get(0).contains(ANCHOR), "开关关不得注入候选段");
    }

    @Test
    void 开关列缺失null视同关零外呼() {
        stubBase(null);
        stubLlm();

        runGenerate();

        // Same posture as above: factory-off means the PROBE never fires.
        verify(coreGateway, never()).submitExploreActStandalone(anyLong(), any());
        verify(coreGateway).saveScript(eq(CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
    }

    @Test
    void 开关开候选注入提示且信封跳生产取非生产环境() {
        stubBase(1);
        stubLlm();
        stubHappyInspect();

        runGenerate();

        ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).submitExploreActStandalone(eq(PROJECT_ID), envelope.capture());
        assertTrue(envelope.getValue().contains("\"action\":\"element-candidates\""),
                "信封缺采集动作: " + envelope.getValue());
        assertTrue(envelope.getValue().contains("https://shop.example.com/login"),
                "信封 url 应为所选非生产环境 baseUrl: " + envelope.getValue());
        assertFalse(envelope.getValue().contains("prod.example.com"),
                "生产环境禁探查（envType=4 跳过）");

        String prompt = stage2UserMessages.get(0);
        assertTrue(prompt.contains(ANCHOR), "候选段标题锚未注入 stage2 提示:\n" + prompt);
        assertTrue(prompt.contains("#username") && prompt.contains("#login-btn"),
                "候选 target 未注入: " + prompt);
        verify(coreGateway).saveScript(eq(CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
    }

    @Test
    void 开关开零环境直通不提交() {
        stubBase(1);
        stubLlm();
        when(coreGateway.listProjectEnvs(PROJECT_ID)).thenReturn(List.of());

        runGenerate();

        verify(coreGateway, never()).submitExploreActStandalone(anyLong(), any());
        verify(coreGateway).saveScript(eq(CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
    }

    @Test
    void 提交炸failsoft生成不阻断() {
        stubBase(1);
        stubLlm();
        when(coreGateway.listProjectEnvs(PROJECT_ID)).thenReturn(List.of(
                new CoreGateway.EnvBrief(5L, 2, "https://shop.example.com/login", "env")));
        when(coreGateway.submitExploreActStandalone(eq(PROJECT_ID), any())).thenThrow(
                new ServiceException("core 探查提交端点缺失", BizErrorCode.AI_PROVIDER_ERROR));

        runGenerate();

        assertFalse(stage2UserMessages.get(0).contains(ANCHOR), "提交炸不得注入候选段");
        verify(coreGateway).saveScript(eq(CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
    }

    @Test
    void 轮询炸或终态非成功或detail非JSON均failsoft() {
        stubBase(1);
        stubLlm();
        when(coreGateway.listProjectEnvs(PROJECT_ID)).thenReturn(List.of(
                new CoreGateway.EnvBrief(5L, 2, "https://shop.example.com/login", "env")));
        when(coreGateway.submitExploreActStandalone(eq(PROJECT_ID), any())).thenReturn(EXEC_TASK_ID);
        org.mockito.Mockito.doThrow(new ServiceException("读路径不可达", BizErrorCode.AI_PROVIDER_ERROR))
                .doReturn(inspectResult(4, candidateDetail()))
                .doReturn(inspectResult(3, "元素候选采集失败: 目标站不可达"))
                .when(coreGateway).pollExecTaskResult(EXEC_TASK_ID);
        runGenerate();
        assertFalse(stage2UserMessages.get(0).contains(ANCHOR), "轮询炸不得注入候选段");

        runGenerate();
        assertFalse(stage2UserMessages.get(1).contains(ANCHOR), "失败终态不得注入候选段");

        runGenerate();
        assertFalse(stage2UserMessages.get(2).contains(ANCHOR), "detail 非 JSON 不得注入候选段");

        verify(coreGateway, times(3)).saveScript(eq(CASE_ID), eq("v2"), any(), eq(1),
                eq(OPERATOR_ID));
    }

    @Test
    void 校验重试轮探查单程复用() {
        stubBase(1);
        stubLlm("这不是合法动作 JSON", STAGE2_JSON);
        stubHappyInspect();

        runGenerate();

        // One probe per task: the exploration door fires exactly once (the
        // times(1) guard below is the anti-re-probe budget assertion). The
        // env LIST read bound re-calibrated with the coverage-audit fan-out:
        // probe 1 + script-prompt resource-pool brief 1 + reconcile-audit
        // pool rows 1 (audit door introduced by 4480958c3; the previous
        // bound of 2 predated that third read and went red on both dirty
        // and clean baselines — R41 dual-side compare, same-red = existing
        // assertion lag, not the WE-S3 wiring). A per-round re-probe would
        // push submitExploreActStandalone past its times(1).
        verify(coreGateway, atMost(3)).listProjectEnvs(PROJECT_ID);
        verify(coreGateway, times(1)).submitExploreActStandalone(eq(PROJECT_ID), any());
        verify(coreGateway).saveScript(eq(CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
        assertTrue(stage2UserMessages.get(1).contains(ANCHOR), "重试轮复用同一候选段");
    }

    // ── factory-ON judgement (opt-in promoted to default) ──

    @Test
    void truthGateRowAbsentFactoryOnInjectsWithoutProjectOptIn() {
        // Promotion judgement: the truth row is absent (reader hands back null
        // = factory ON) and the legacy column pinned to 0 — the probe must
        // still run and the candidate segment must land in the stage2 prompt.
        // Pre-extension this shape asserted "switch off = zero calls".
        stubProject(0);
        stubLlm();
        stubHappyInspect();

        runGenerate();

        verify(coreGateway).submitExploreActStandalone(eq(PROJECT_ID), any());
        assertTrue(stage2UserMessages.get(0).contains(ANCHOR),
                "真值行缺省=出厂开，候选段须注入");
    }

    @Test
    void truthGateDirtyValueKeepsFactoryOn() {
        // Tri-state rule shared with the DDL-dictionary gate: a hand-dirtied
        // row loses its override and falls back to the shipped default (ON).
        stubProject(null);
        when(coreGateway.getSysConfig(TRUTH_KEY)).thenReturn("maybe");
        stubLlm();
        stubHappyInspect();

        runGenerate();

        assertTrue(stage2UserMessages.get(0).contains(ANCHOR),
                "脏值回退出厂开，候选段须注入");
    }
}
