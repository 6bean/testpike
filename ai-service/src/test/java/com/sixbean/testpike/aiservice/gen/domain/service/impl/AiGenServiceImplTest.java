package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.common.util.JsonOutputExtractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptLimitResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.support.GenTestFixtures;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AiGenServiceImplTest {

    private static final long TASK_ID = 41L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long REQUIREMENT_ID = 66L;
    private static final long NEW_CASE_ID = 900L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final AiGenServiceImpl service = new AiGenServiceImpl(
            llmChain, llmRouter, coreGateway, new ObjectMapper(),
            new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
            new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
            new ScriptLimitResolver(coreGateway));

    private static LlmEndpointConfig endpoint() {
        return GenTestFixtures.endpoint();
    }

    private static AiGenMessage message(int taskType, Long requirementId, Long caseId) {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, taskType,
                "用户可以使用正确的账号密码登录系统", requirementId, caseId);
    }

    
    private static String stage1Json() {
        return GenTestFixtures.stage1Json();
    }

    
    private static String stage2Json() {
        return GenTestFixtures.stage2Json();
    }

    private void stubStage1Stage2() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-900"));
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    String content = system.contains("STAGE1_CASE_JSON") ? stage1Json() : stage2Json();
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L),
                            endpoint());
                });
    }

    
    @Test
    void generate_消息含需求点ID_落库后自动建链() {
        stubStage1Stage2();
        when(coreGateway.getRequirement(REQUIREMENT_ID)).thenReturn(
                new CoreGateway.RequirementBrief(REQUIREMENT_ID, "登录需求", "登录成功跳转首页"));

        service.generate(message(1, REQUIREMENT_ID, null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> caseIds = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).linkRequirement(eq(REQUIREMENT_ID), caseIds.capture(), eq(OPERATOR_ID));
        assertEquals(List.of(NEW_CASE_ID), caseIds.getValue(), "关联必须挂本次落库的新用例 ID");
    }

    
    @Test
    void generate_消息无需求点ID_不建链主链照常() {
        stubStage1Stage2();

        service.generate(message(1, null, null));

        verify(coreGateway, never()).linkRequirement(anyLong(), any(), anyLong());
        verify(coreGateway, never()).getRequirement(anyLong());
        verify(coreGateway).markDone(TASK_ID, NEW_CASE_ID);
    }

    
    @Test
    void generateScriptOnly_不得误建需求关联() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getCase(NEW_CASE_ID)).thenReturn(Map.of("title", "正确账号密码登录成功"));
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenReturn(new LlmChatHandle(
                        new LlmChatResult(stage2Json(), "openai", "routed-model", 100, 50, 20L),
                        endpoint()));

        service.generate(message(2, null, NEW_CASE_ID));

        verify(coreGateway, never()).linkRequirement(anyLong(), any(), anyLong());
        verify(coreGateway).saveScript(eq(NEW_CASE_ID), eq("v2"), contains("\"version\":\"v2\""), eq(1),
                eq(OPERATOR_ID));
        verify(coreGateway).markDone(TASK_ID, NEW_CASE_ID);
    }

    
    @Test
    void generate_stage2Prompt含locatorChain结构示例() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-900"));
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    boolean stage1 = system.contains("STAGE1_CASE_JSON");
                    return new LlmChatHandle(
                            new LlmChatResult(stage1 ? stage1Json() : stage2Json(),
                                    "openai", "routed-model", 100, 50, 20L),
                            endpoint());
                });

        service.generate(message(1, null, null));

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, org.mockito.Mockito.times(2))
                .chatHandle(captor.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class));
        ChatCompletionRequest stage2Request = captor.getAllValues().stream()
                .filter(r -> r.messages().get(0).content().contains("STAGE2_ACTION_JSON"))
                .findFirst().orElseThrow(() -> new AssertionError("未捕获 stage2 请求"));
        String userPrompt = stage2Request.messages().get(1).content();
        assertTrue(userPrompt.contains("\"chain\":["),
                "stage2 user prompt 须含 locator.chain 结构示例片段（tick 95 缺口 B）: " + userPrompt);
    }

    
    @Test
    void generate_stage2Prompt含顶层env结构示例() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-900"));
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    boolean stage1 = system.contains("STAGE1_CASE_JSON");
                    return new LlmChatHandle(
                            new LlmChatResult(stage1 ? stage1Json() : stage2Json(),
                                    "openai", "routed-model", 100, 50, 20L),
                            endpoint());
                });

        service.generate(message(1, null, null));

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, org.mockito.Mockito.times(2))
                .chatHandle(captor.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class));
        ChatCompletionRequest stage2Request = captor.getAllValues().stream()
                .filter(r -> r.messages().get(0).content().contains("STAGE2_ACTION_JSON"))
                .findFirst().orElseThrow(() -> new AssertionError("未捕获 stage2 请求"));
        String userPrompt = stage2Request.messages().get(1).content();
        assertTrue(userPrompt.contains("\"env\":{\""),
                "stage2 user prompt 须含顶层 env 结构示例片段（tick 98 缺口 C）: " + userPrompt);
    }

    /**
 * Assertion face ⑦ ( S4 window attribution, identical goto-env rejection
 * three runs in a row): vendors converge on gold samples (the /98 gap B/C
 * lesson above) yet every stage2 sample belongs to the expect/console/net/
 * http/sql families — the prompt never showed a goto step, so the model
 * parks the address wherever it likes and the validator keeps naming
 * user prompt; validator stays untouched.
 */
    @Test
    void generate_stage2PromptCarriesGotoGoldSample() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-901"));
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    boolean stage1 = system.contains("STAGE1_CASE_JSON");
                    return new LlmChatHandle(
                            new LlmChatResult(stage1 ? stage1Json() : stage2Json(),
                                    "openai", "routed-model", 100, 50, 20L),
                            endpoint());
                });

        service.generate(message(1, null, null));

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, org.mockito.Mockito.times(2))
                .chatHandle(captor.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class));
        ChatCompletionRequest stage2Request = captor.getAllValues().stream()
                .filter(r -> r.messages().get(0).content().contains("STAGE2_ACTION_JSON"))
                .findFirst().orElseThrow(() -> new AssertionError("未捕获 stage2 请求"));
        String userPrompt = stage2Request.messages().get(1).content();
        assertTrue(userPrompt.contains("\"action\":\"goto\""),
                "stage2 user prompt 须含 goto 步黄金样例骨架（T594 归因轮）: " + userPrompt);
    }

    
    @Test
    void generate_围栏包裹输出_落库内容必须归一化JSON() throws Exception {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-900"));
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    String content = system.contains("STAGE1_CASE_JSON")
                            ? stage1Json()
                            : "```json\n" + stage2Json() + "\n```";
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L),
                            endpoint());
                });

        service.generate(message(1, null, null));

        ArgumentCaptor<String> scriptJson = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).saveScript(eq(NEW_CASE_ID), eq("v2"), scriptJson.capture(), eq(1),
                eq(OPERATOR_ID));
        String saved = scriptJson.getValue();
        assertTrue(!saved.contains("```"),
                "落库内容必须剥除围栏（tick 101 缺口 D：JSONB 落库 500），现状=" + saved);
        assertTrue(new ObjectMapper().readTree(saved).has("version"),
                "落库内容必须是可解析的合法 v2 JSON（readTree 归一化实证）");
    }

    
    @Test
    void generate_降级生效端点_modelName二次回填不失真() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-900"));
        LlmEndpointConfig standby = new LlmEndpointConfig("standby", "standby-model", "dummy-key",
                "http://localhost:4100/v1", LlmEndpointConfig.SOURCE_CHAIN);
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String content = r.messages().get(0).content().contains("STAGE1_CASE_JSON")
                            ? stage1Json() : stage2Json();
                    return new LlmChatHandle(
                            new LlmChatResult(content, "standby", "standby-model", 100, 50, 20L),
                            standby);
                });

        service.generate(message(1, null, null));

        verify(coreGateway).markModel(TASK_ID, "routed-model");
        verify(coreGateway).markModel(TASK_ID, "standby-model");
    }

    
    @Test
    void generate_失败原因超长必须截断回写不卡死() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        String longError = "AI 供应商调用失败: " + "重".repeat(600);
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenThrow(new ServiceException(longError, 50201));

        assertThrows(ServiceException.class, () -> service.generate(message(1, null, null)));

        ArgumentCaptor<String> errorMsg = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).markFailed(eq(TASK_ID), eq("FORMAT_ERROR"), errorMsg.capture());
        assertTrue(errorMsg.getValue().length() <= 500,
                "失败原因必须截断至 core 契约上限 500，实际=" + errorMsg.getValue().length());
        assertTrue(errorMsg.getValue().contains("AI 供应商调用失败"),
                "截断保留头部关键信息：" + errorMsg.getValue().substring(0, 40));
    }

    
    @Test
    void generate_调用降级链必须携带操作人通知上下文() {
        stubStage1Stage2();

        service.generate(message(1, null, null));

        ArgumentCaptor<LlmFallbackNotice> notice =
                ArgumentCaptor.forClass(LlmFallbackNotice.class);
        verify(llmChain, org.mockito.Mockito.times(2))
                .chatHandle(any(), eq(PROJECT_ID), notice.capture());
        assertEquals(OPERATOR_ID, notice.getAllValues().get(0).userId(),
                "通知归属=消息发起人（站内信收件人）");
        assertTrue(notice.getAllValues().get(0).title().contains(String.valueOf(TASK_ID)),
                "通知标题含任务号可追溯: " + notice.getAllValues().get(0).title());
    }


    
    private String captureStage2UserPrompt() {
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, org.mockito.Mockito.atLeast(2))
                .chatHandle(captor.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class));
        return captor.getAllValues().stream()
                .filter(r -> r.messages().get(0).content().contains("STAGE2_ACTION_JSON"))
                .findFirst().orElseThrow(() -> new AssertionError("未捕获 stage2 请求"))
                .messages().get(1).content();
    }

    
    @Test
    void generate_stage2Prompt注入热更后的ai脚本限额九键() {
        stubStage1Stage2();
        when(coreGateway.getSysConfig("ai.script.max-steps")).thenReturn("15");
        when(coreGateway.getSysConfig("ai.script.max-nest-depth")).thenReturn("4");
        when(coreGateway.getSysConfig("ai.script.max-loop")).thenReturn("3");
        when(coreGateway.getSysConfig("ai.script.assert-timeout-sec")).thenReturn("8");
        when(coreGateway.getSysConfig("ai.script.default-wait-sec")).thenReturn("2");
        when(coreGateway.getSysConfig("ai.script.smart-wait")).thenReturn("false");
        when(coreGateway.getSysConfig("ai.script.auto-scroll")).thenReturn("false");
        when(coreGateway.getSysConfig("ai.script.screenshot-mode")).thenReturn("1");
        when(coreGateway.getSysConfig("ai.script.fail-policy")).thenReturn("2");

        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertTrue(userPrompt.contains("步骤 ≤15"), "须含热更步骤上限: " + userPrompt);
        assertFalse(userPrompt.contains("≤50"), "禁残留硬编码 50 双源: " + userPrompt);
        assertTrue(userPrompt.contains("嵌套 ≤4"), "须含热更嵌套上限: " + userPrompt);
        assertTrue(userPrompt.contains("loop ≤3"), "TC-AIS-02 注入 loop 上限: " + userPrompt);
        assertTrue(userPrompt.contains("断言超时 8s"), "须含热更断言超时: " + userPrompt);
        assertTrue(userPrompt.contains("默认等待 2s"), "须含热更默认等待: " + userPrompt);
        assertTrue(userPrompt.contains("智能等待关闭"), "bool 键 true/false→人话开启/关闭: " + userPrompt);
        assertTrue(userPrompt.contains("自动滚动关闭"), "bool 键注入: " + userPrompt);
        assertTrue(userPrompt.contains("截图策略=每步"), "screenshot-mode 1=每步: " + userPrompt);
        assertTrue(userPrompt.contains("失败策略=立即中止"), "fail-policy 2=立即中止: " + userPrompt);
    }

    
    @Test
    void generate_限额键不可读时stage2Prompt回退契约默认() {
        stubStage1Stage2();

        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertTrue(userPrompt.contains("步骤 ≤50"), "默认步骤上限 50: " + userPrompt);
        assertTrue(userPrompt.contains("嵌套 ≤5"), "默认嵌套上限 5: " + userPrompt);
        assertTrue(userPrompt.contains("loop ≤20"), "默认 loop 上限 20: " + userPrompt);
        assertTrue(userPrompt.contains("断言超时 10s"), "默认断言超时: " + userPrompt);
        assertTrue(userPrompt.contains("默认等待 3s"), "默认等待: " + userPrompt);
        assertTrue(userPrompt.contains("智能等待开启"), "默认 bool: " + userPrompt);
        assertTrue(userPrompt.contains("自动滚动开启"), "默认 bool: " + userPrompt);
        assertTrue(userPrompt.contains("截图策略=仅失败"), "默认截图策略 3: " + userPrompt);
        assertTrue(userPrompt.contains("失败策略=继续后续步骤"), "默认失败策略 1: " + userPrompt);
    }

    
    @Test
    void generate_限额每次生成即读跨任务热更不缓存() {
        stubStage1Stage2();
        when(coreGateway.getSysConfig("ai.script.max-steps")).thenReturn("15");
        service.generate(message(1, null, null));
        assertTrue(captureStage2UserPrompt().contains("步骤 ≤15"),
                "第一轮须携带首轮热更值");

        Mockito.clearInvocations(llmChain);
        when(coreGateway.getSysConfig("ai.script.max-steps")).thenReturn("200");
        service.generate(message(1, null, null));
        String second = captureStage2UserPrompt();
        assertTrue(second.contains("步骤 ≤200"), "热更后禁缓存旧值: " + second);
        assertFalse(second.contains("≤15"), "旧值须消失: " + second);
    }


    
    @Test
    void generate_脚本校验失败重试时stage2Prompt注入上次校验错误() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-900"));
        java.util.concurrent.atomic.AtomicInteger stage2Calls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    String content;
                    if (system.contains("STAGE1_CASE_JSON")) {
                        content = stage1Json();
                    } else {
                        content = stage2Calls.incrementAndGet() == 1
                                ? stage2Json().replace("\"version\":\"v2\"", "\"version\":\"v1\"")
                                : stage2Json();
                    }
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L),
                            endpoint());
                });

        service.generate(message(1, null, null));

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, org.mockito.Mockito.times(3))
                .chatHandle(captor.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class));
        List<String> stage2Prompts = captor.getAllValues().stream()
                .filter(r -> r.messages().get(0).content().contains("STAGE2_ACTION_JSON"))
                .map(r -> r.messages().get(1).content())
                .toList();
        assertEquals(2, stage2Prompts.size(), "一次失败一次成功=stage2 共 2 次调用");
        assertFalse(stage2Prompts.get(0).contains("上次脚本不合格"),
                "首轮提示词禁携带反馈: " + stage2Prompts.get(0));
        assertTrue(stage2Prompts.get(1).contains("上次脚本不合格"),
                "第二轮须注入修复反馈: " + stage2Prompts.get(1));
        assertTrue(stage2Prompts.get(1).contains("version 必须为 v2"),
                "反馈须点名上一轮校验错误原文: " + stage2Prompts.get(1));
    }

    
    @Test
    void generate_stage2Prompt不含省略号毒示例且给合法占位正例() {
        stubStage1Stage2();

        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertFalse(userPrompt.contains("{{ENV:...}}"),
                "禁字面省略号毒示例（模型逐字复制产非法占位）: " + userPrompt);
        assertTrue(userPrompt.contains("{{ENV:"),
                "须给 ENV 占位合法正例: " + userPrompt);
    }

    
    @Test
    void generate_stage2Prompt明确setup为步骤数组() {
        stubStage1Stage2();

        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertTrue(userPrompt.contains("setup 为步骤数组"),
                "须显式表述 setup 数组形态: " + userPrompt);
    }


    
    @Test
    void generate_stage2Prompt含B10断言映射规则() {
        stubStage1Stage2();

        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertTrue(userPrompt.contains("matchType=range"),
                "须含区间语义→range 映射规则: " + userPrompt);
        assertTrue(userPrompt.contains("bounds"),
                "须含 bounds 开闭映射说明: " + userPrompt);
        assertTrue(userPrompt.contains("matchType=regex"),
                "须含形态语义→regex 映射规则: " + userPrompt);
        assertTrue(userPrompt.contains("需人工精化"),
                "须含 regex 失败回退 contains+人工精化标注规则: " + userPrompt);
        assertTrue(userPrompt.contains("待 B8"),
                "须含跨字段→code.step 候选+待 B8 注记规则: " + userPrompt);
    }

    
    @Test
    void generate_stage2Prompt含B10黄金样例四组() {
        stubStage1Stage2();

        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertTrue(userPrompt.contains("\"bounds\":\"open\""),
                "样例①须含双开区间产物骨架: " + userPrompt);
        assertTrue(userPrompt.contains("\"bounds\":\"left-open\"")
                        || userPrompt.contains("\"bounds\":\"right-open\""),
                "样例②须含单开边界产物骨架: " + userPrompt);
        assertTrue(userPrompt.contains("\"pattern\""),
                "样例③须含 regex pattern 产物骨架: " + userPrompt);
        assertTrue(userPrompt.contains("需人工精化"),
                "样例④回退标注须出现: " + userPrompt);
    }

    
    @Test
    void generate_stage2Prompt含无据诚实标注条款() {
        stubStage1Stage2();

        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertTrue(userPrompt.contains("无据诚实标注纪律"),
                "出厂开态提示词须含无据诚实标注条款: " + userPrompt);
    }

    
    @Test
    void generate_stage2Prompt诚实标注键关零注入() {
        stubStage1Stage2();
        when(coreGateway.getSysConfig("ai.gen.no-truth-honest-mark.enabled"))
                .thenReturn("false");

        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertTrue(!userPrompt.contains("无据诚实标注纪律"),
                "关态提示词不得注入诚实标注条款段: " + userPrompt);
    }


    
    @Test
    void generate_闸关时stage2Prompt含非UI三动作与人工SQL通道指令() {
        stubStage1Stage2();
        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertTrue(userPrompt.contains("console.assert"), "须含 console.assert 动作: " + userPrompt);
        assertTrue(userPrompt.contains("net.assert"), "须含 net.assert 动作: " + userPrompt);
        assertTrue(userPrompt.contains("http.request"), "须含 http.request 动作: " + userPrompt);
        assertFalse(userPrompt.contains("sql.assert"), "闸关禁产 sql.assert: " + userPrompt);
        assertFalse(userPrompt.contains("sql.query"), "闸关禁产 sql.query: " + userPrompt);
        assertTrue(userPrompt.contains("sql.exec") && userPrompt.contains("禁"),
                "sql.exec AI 禁自动产规则须恒在: " + userPrompt);
        assertTrue(userPrompt.contains("人工"), "闸关须给人工通道指令: " + userPrompt);
    }

    
    @Test
    void generate_闸开时stage2Prompt含SQL族映射与DDL注入段() {
        when(coreGateway.getSysConfig("ai.sql-gen.enabled")).thenReturn("1");
        stubStage1Stage2();
        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertTrue(userPrompt.contains("sql.assert"), "闸开须含 sql.assert: " + userPrompt);
        assertTrue(userPrompt.contains("sql.query"), "闸开须含 sql.query: " + userPrompt);
        assertTrue(userPrompt.contains("具名绑定"), "sql 参数须含 :name 具名绑定规则: " + userPrompt);
    }

    
    @Test
    void generate_stage2Prompt含B11时序三分表() {
        stubStage1Stage2();
        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertTrue(userPrompt.contains("即时一次判定"),
                "须含 console/net 即时判定语义: " + userPrompt);
        assertTrue(userPrompt.contains("轮询等待"),
                "须含 sql/http poll 轮询语义: " + userPrompt);
        assertTrue(userPrompt.contains("interval≥1s") || userPrompt.contains("interval ≥1s"),
                "轮询下限钳制须表述: " + userPrompt);
    }

    
    @Test
    void generate_stage2Prompt含B11黄金样例特征() {
        stubStage1Stage2();
        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertTrue(userPrompt.contains("ignorePatterns"),
                "样例①噪音白名单特征: " + userPrompt);
        assertTrue(userPrompt.contains("absent"),
                "样例②net 负向一等公民特征: " + userPrompt);
        assertTrue(userPrompt.contains("saveAs"),
                "样例③http 变量声明特征: " + userPrompt);
        assertTrue(userPrompt.contains("{{VAR:"),
                "样例③变量引用形态特征: " + userPrompt);
    }

    
    @Test
    void generate_stage2Prompt含变量链声明序纪律() {
        stubStage1Stage2();
        service.generate(message(1, null, null));

        String userPrompt = captureStage2UserPrompt();
        assertTrue(userPrompt.contains("未定义引用"),
                "须含未定义引用自检纪律: " + userPrompt);
        assertTrue(userPrompt.contains("capture"),
                "须含 UI expect capture 回读源: " + userPrompt);
    }
}
