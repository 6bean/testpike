package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.common.util.ActionJsonValidator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptLimitResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.aictx.domain.component.CtxMemoryHitWriter;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxProperties;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import com.sixbean.testpike.aiservice.knowledge.domain.component.StyleFewshotBridge;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeSearchService;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmPromptAssembler;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.domain.component.SkillAssemblyBridge;
import com.sixbean.testpike.aiservice.llm.domain.component.SkillUsageRecorder;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenCounter;
import com.sixbean.testpike.aiservice.doc.domain.component.ContextBudgetPlanner;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AiGenServiceImplFewshotWiringTest {

    private static final long TASK_ID = 91L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 901L;
    private static final String SWITCH_KEY = "ai.kb.style-fewshot.enabled";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final SkillAssemblyBridge skillBridge = new SkillAssemblyBridge(coreGateway,
            new LlmPromptAssembler(new TokenCounter()), new SkillUsageRecorder(coreGateway, mock(CtxMemoryHitWriter.class)), new AiCtxConfigResolver(coreGateway, new AiCtxProperties()));

    private final AtomicInteger searchCalls = new AtomicInteger();
    
    private volatile List<KnowledgeSearchItem> hits = List.of();

    private final StyleFewshotBridge fewshotBridge = new StyleFewshotBridge(
            (KnowledgeSearchService) request -> {
                searchCalls.incrementAndGet();
                return hits;
            }, coreGateway, new TokenCounter(), null);

    private final AiGenServiceImpl service = new AiGenServiceImpl(
            llmChain, llmRouter, coreGateway, new ObjectMapper(),
            new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
            new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
            new ScriptLimitResolver(coreGateway), skillBridge, null, null, null,
            fewshotBridge);

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static KnowledgeSearchItem caseItem(long caseId, String content) {
        return new KnowledgeSearchItem("c-" + caseId, caseId, caseId, "TEST_CASE",
                "用例" + caseId, content, 0.8, 0.9);
    }

    private static final String STAGE1_JSON = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"点击登录","isAssert":0},
                      {"stepNo":3,"description":"断言首页","isAssert":1}],
             "expectedResult":"跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    private static final String STAGE2_JSON = "{\"version\":\"v2\",\"caseId\":\"TC-REG-001\",\"endType\":\"web\","
            + "\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\"{{ENV:URL}}/login\"}}],"
            + "\"steps\":[{\"action\":\"input\",\"target\":{\"css\":\"#username\",\"chain\":[\"css\"]},"
            + "\"value\":\"u\"},{\"action\":\"expect\",\"target\":{\"text\":\"首页\"}}],"
            + "\"env\":{\"ENV\":\"dev\"}}";

    private final List<String> userMessages = new ArrayList<>();
    private final List<String> systemMessages = new ArrayList<>();

    private void stubGenBase() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-901"));
        when(coreGateway.getSysConfig(SWITCH_KEY)).thenReturn("true");
    }

    
    private void stubLlm(String... stage1Outputs) {
        int[] idx = {0};
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    String user = r.messages().get(1).content();
                    systemMessages.add(system);
                    userMessages.add(user);
                    String content = system.contains("STAGE1_CASE_JSON")
                            ? stage1Outputs[Math.min(idx[0]++, stage1Outputs.length - 1)]
                            : STAGE2_JSON;
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "m", 100, 50, 20L), endpoint());
                });
    }

    @Test
    void 开启态stage1用户提示含fewshot段且system含SKILL段并存() {
        stubGenBase();
        hits = List.of(caseItem(41L, "优秀登录用例正文QQ"));
        when(coreGateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of(
                new SkillAssemblyRemoteItem(51L, "SK51", 3, 5, 1, 1, "通用文案规范MM", 0)));
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        String stage1User = userMessages.get(0);
        assertTrue(stage1User.contains("优秀登录用例正文QQ"),
                "few-shot 命中正文未注入 stage1 user prompt：\n" + stage1User);
        String stage1System = systemMessages.get(0);
        assertTrue(stage1System.contains("通用文案规范MM"),
                "SKILL 段须仍在 system（TC-A4-02 两段并存互不破坏）");
        assertTrue(stage1System.contains("STAGE1_CASE_JSON"),
                "MockLlmProvider 契约位（system 标记）不得被注入破坏");
    }

    @Test
    void 关闭态零注入生成不失败() {
        stubGenBase();
        when(coreGateway.getSysConfig(SWITCH_KEY)).thenReturn("false");
        hits = List.of(caseItem(42L, "不应出现的正文WW"));
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        assertEquals(0, searchCalls.get(), "开关关闭不得触发检索");
        assertTrue(userMessages.get(0).contains("登录需求"));
        assertTrue(userMessages.stream().noneMatch(u -> u.contains("不应出现的正文WW")),
                "关闭态零注入");
        verifyCaseCreated();
    }

    @Test
    void 预算超限丢尾项warn生成不失败() {
        stubGenBase();
        when(coreGateway.getSysConfig(ContextBudgetPlanner.BUDGET_CONFIG_KEY)).thenReturn("80");
        hits = List.of(
                caseItem(43L, "首项保留正文"),
                caseItem(44L, "尾项丢弃正文" + "尾".repeat(120)));
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        String stage1User = userMessages.get(0);
        assertTrue(stage1User.contains("首项保留正文"), "预算内首项须保留");
        assertTrue(!stage1User.contains("尾项丢弃正文"), "超预算尾项须整项丢弃（禁半残截断）");
        verifyCaseCreated();
    }

    @Test
    void 重试轮复用同一fewshot段禁重复检索() {
        stubGenBase();
        hits = List.of(caseItem(45L, "重试复用正文RR"));
        stubLlm("{bad json", STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        // S2 segment B: the pool now carries one retrieval per stage —
        // stage1 once (reused by its retry rounds, the point of this suite)
        // plus stage2 once (query = stored case body). A third call would mean
        // a retry round re-burned the search budget.
        assertEquals(2, searchCalls.get(),
                "stage1 重试轮复用零第三检索 + stage2 单任务一次（片B 用例级 few-shot）");
        List<String> stage1Users = userMessages.stream()
                .filter(u -> u.contains("请针对以下需求生成")).toList();
        assertEquals(2, stage1Users.size(), "应经历一次解析重试");
        assertTrue(stage1Users.stream().allMatch(u -> u.contains("重试复用正文RR")),
                "两轮 stage1 user prompt 均须含同一注入段");
    }

    
    private void verifyCaseCreated() {
        verify(coreGateway).createCase(eq(OPERATOR_ID), any());
    }
}
