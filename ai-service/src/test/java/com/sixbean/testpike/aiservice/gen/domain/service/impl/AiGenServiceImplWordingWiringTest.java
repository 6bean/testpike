package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.PromptTemplateResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.PromptWordingAdoption;
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
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AiGenServiceImplWordingWiringTest {

    private static final long TASK_ID = 96L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 961L;
    private static final String ANCHOR = "期望表述约束（评审采纳，必须遵循）：";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final PromptWordingAdoption wordingAdoption =
            new PromptWordingAdoption(coreGateway, new ObjectMapper());
    private final PromptTemplateResolver promptTemplateResolver =
            new PromptTemplateResolver(coreGateway);

    private final AiGenServiceImpl service = new AiGenServiceImpl(
            llmChain, llmRouter, coreGateway, new ObjectMapper(),
            new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
            new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
            new ScriptLimitResolver(coreGateway), null, null, null, null, null, null,
            promptTemplateResolver, wordingAdoption);

    private final List<String> userMessages = new ArrayList<>();

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG);
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

    private void stubBase() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-961"));
        when(coreGateway.getSysConfig(any())).thenReturn(null);
    }

    private void stubLlm(String... stage1Outputs) {
        int[] idx = {0};
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    userMessages.add(r.messages().get(1).content());
                    String content = r.messages().get(0).content().contains("STAGE1_CASE_JSON")
                            ? stage1Outputs[Math.min(idx[0]++, stage1Outputs.length - 1)]
                            : STAGE2_JSON;
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "m", 100, 50, 20L), endpoint());
                });
    }

    private static CoreGateway.WordingPair pair(String remark, String diffChangesJson) {
        return new CoreGateway.WordingPair(91L, 92L, remark, diffChangesJson,
                "2026-09-25 10:00:00");
    }

    @Test
    void 默认路stage1用户提示注入措辞约束段() {
        stubBase();
        when(coreGateway.wordingPairs(eq(PROJECT_ID), anyInt())).thenReturn(List.of(
                pair("标题应体现登录场景", "[{\"field\":\"title\",\"label\":\"用例名称\","
                        + "\"before\":\"登录\",\"after\":\"账号密码登录成功\"}]")));
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        String stage1User = userMessages.get(0);
        assertTrue(stage1User.contains(ANCHOR), "标题锚未注入默认路 stage1：\n" + stage1User);
        assertTrue(stage1User.contains("标题应体现登录场景"), "反馈半句未注入");
        assertTrue(stage1User.contains("账号密码登录成功"), "修正行未注入");
    }

    @Test
    void 模板路wording占位符渲染注入() {
        stubBase();
        when(coreGateway.wordingPairs(eq(PROJECT_ID), anyInt())).thenReturn(
                List.of(pair("预期结果须可断言", null)));
        when(coreGateway.getTaskTemplatePin(TASK_ID)).thenReturn(7L);
        when(coreGateway.promptTemplateDetail(7L)).thenReturn(new CoreGateway.PromptTemplateBrief(
                7L, PromptTemplateResolver.STAGE1_TEMPLATE_NAME,
                PromptTemplateResolver.TEMPLATE_DOMAIN, 2,
                "生成用例：{{requirement}}\n{{wording}}{{lang}}", 2));
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        String stage1User = userMessages.get(0);
        assertTrue(stage1User.contains(ANCHOR) && stage1User.contains("预期结果须可断言"),
                "模板路 {{wording}} 位未渲染：\n" + stage1User);
        assertTrue(!stage1User.contains("{{wording}}"), "占位符须被替换不留原文");
    }

    @Test
    void 零采纳对提示词无锚零注入() {
        stubBase();
        when(coreGateway.wordingPairs(eq(PROJECT_ID), anyInt())).thenReturn(List.of());
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        assertTrue(userMessages.stream().noneMatch(u -> u.contains(ANCHOR)), "零对禁悬空锚");
        verify(coreGateway).createCase(eq(OPERATOR_ID), any());
    }

    @Test
    void 读路径异常生成不失败failsoft() {
        stubBase();
        when(coreGateway.wordingPairs(anyLong(), anyInt()))
                .thenThrow(new RuntimeException("core 不可达"));
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        verify(coreGateway).createCase(eq(OPERATOR_ID), any());
        assertTrue(userMessages.stream().noneMatch(u -> u.contains(ANCHOR)), "异常态零注入");
    }

    @Test
    void 重试轮复用同一措辞段禁重复读路径() {
        stubBase();
        when(coreGateway.wordingPairs(eq(PROJECT_ID), anyInt())).thenReturn(
                List.of(pair("步骤描述缺主体", null)));
        stubLlm("{bad json", STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        verify(coreGateway, times(1)).wordingPairs(eq(PROJECT_ID), anyInt());
        List<String> stage1Users = userMessages.stream()
                .filter(u -> u.contains("请针对以下需求生成")).toList();
        assertTrue(stage1Users.size() == 2 && stage1Users.stream()
                .allMatch(u -> u.contains(ANCHOR)), "两轮 stage1 均须含同一注入段");
    }
}
