package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.PromptTemplateResolver;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AiGenServiceImplTemplateWiringTest {

    private static final long TASK_ID = 93L;
    private static final long TASK_ID_B = 94L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 903L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);

    private final AiGenServiceImpl service = new AiGenServiceImpl(
            llmChain, llmRouter, coreGateway, new ObjectMapper(),
            new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
            new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
            new ScriptLimitResolver(coreGateway), null, null, null, null, null, null,
            new PromptTemplateResolver(coreGateway));

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static CoreGateway.PromptTemplateBrief template(long id, String name, String content) {
        return new CoreGateway.PromptTemplateBrief(id, name, PromptTemplateResolver.TEMPLATE_DOMAIN,
                1, content, 2);
    }

    private static final String STAGE1_JSON = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"点击登录","isAssert":0},
                      {"stepNo":3,"description":"断言首页","isAssert":1}],
             "expectedResult":"跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    private static final String STAGE2_JSON = "{\"version\":\"v2\",\"caseId\":\"TC-TPL-001\",\"endType\":\"web\","
            + "\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\"{{ENV:URL}}/login\"}}],"
            + "\"steps\":[{\"action\":\"input\",\"target\":{\"css\":\"#username\",\"chain\":[\"css\"]},"
            + "\"value\":\"u\"},{\"action\":\"expect\",\"target\":{\"text\":\"首页\"}}],"
            + "\"env\":{\"ENV\":\"dev\"}}";

    private final List<String> stage1Users = new ArrayList<>();
    private final List<String> stage2Users = new ArrayList<>();

    private void stubGenBase() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "tpl", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-tpl-903"));
        when(coreGateway.getTaskTemplatePin(anyLong())).thenReturn(null);
    }

    
    private void stubLlm(String... stage1Outputs) {
        int[] idx = {0};
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    boolean stage1 = system.contains("STAGE1_CASE_JSON");
                    (stage1 ? stage1Users : stage2Users).add(r.messages().get(1).content());
                    String content = stage1
                            ? stage1Outputs[Math.min(idx[0]++, stage1Outputs.length - 1)]
                            : STAGE2_JSON;
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "m", 100, 50, 20L), endpoint());
                });
    }

    
    private void stubFullBucket(String name,
                                AtomicReference<CoreGateway.PromptTemplateBrief> current) {
        when(coreGateway.getSysConfig(PromptTemplateResolver.TRAFFIC_PCT_KEY)).thenReturn("0");
        when(coreGateway.promptTemplateLatestByStatus(eq(name),
                eq(PromptTemplateResolver.TEMPLATE_DOMAIN), eq(2)))
                .thenAnswer(inv -> {
                    CoreGateway.PromptTemplateBrief row = current.get();
                    return row == null ? null : new CoreGateway.PromptTemplateBrief(
                            row.id(), name, row.domain(), row.version(), row.content(), row.status());
                });
    }

    @Test
    void 无模板态回退内置默认骨架存量文本零变化() {
        stubGenBase();
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        String stage1 = stage1Users.get(0);
        assertTrue(stage1.contains("请针对以下需求生成 1 条结构化测试用例："),
                "stage1 默认骨架缺失：\n" + stage1);
        assertTrue(stage1.contains("输出字段：title（测试目标）"), "stage1 默认契约段缺失");
        String stage2 = stage2Users.get(0);
        assertTrue(stage2.contains("请将以下测试用例转换为动作 JSON v2："),
                "stage2 默认骨架缺失：\n" + stage2);
        assertTrue(stage2.contains("断言映射规则（动作 JSON v2 格式的 matchType"), "stage2 默认映射规则段缺失");
        assertTrue(stage2.contains("时序三分表"), "stage2 默认时序段缺失");
        verify(coreGateway).createCase(eq(OPERATOR_ID), any());
        verify(coreGateway, never()).pinTemplate(anyLong(), anyLong());
    }

    @Test
    void 模板在场stage1骨架由模板渲染且占位符动态段照常注入并写钉() {
        stubGenBase();
        AtomicReference<CoreGateway.PromptTemplateBrief> s1 = new AtomicReference<>(
                template(1L, PromptTemplateResolver.STAGE1_TEMPLATE_NAME,
                        "【定制S1骨架】需求段：{{requirement}}\n验收：{{acceptanceCriteria}}"
                                + "内容语言：{{lang}}\n{{feedback}}"));
        stubFullBucket(PromptTemplateResolver.STAGE1_TEMPLATE_NAME, s1);
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        String stage1 = stage1Users.get(0);
        assertTrue(stage1.contains("【定制S1骨架】需求段：登录需求"),
                "定制模板骨架+requirement 渲染缺失：\n" + stage1);
        assertTrue(stage1.contains("内容语言：zh-CN"), "lang 占位符渲染缺失");
        assertFalse(stage1.contains("输出字段：title（测试目标）"),
                "模板在场时默认契约文本位不得再出现（单源=模板，禁双源拼接）");
        verify(coreGateway).pinTemplate(TASK_ID, 1L);
    }

    @Test
    void 模板在场stage2骨架由模板渲染且用例块照常注入() {
        stubGenBase();
        AtomicReference<CoreGateway.PromptTemplateBrief> s2 = new AtomicReference<>(
                template(2L, PromptTemplateResolver.STAGE2_TEMPLATE_NAME,
                        "【定制S2骨架】\n{{caseBlock}}约束：{{scriptLimits}}\n{{feedback}}"));
        stubFullBucket(PromptTemplateResolver.STAGE2_TEMPLATE_NAME, s2);
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        String stage2 = stage2Users.get(0);
        assertTrue(stage2.contains("【定制S2骨架】"), "定制 stage2 骨架缺失：\n" + stage2);
        assertTrue(stage2.contains("用例标题：登录成功"), "caseBlock 占位符渲染缺失");
        assertTrue(stage2.contains("约束：单用例步骤 ≤"), "scriptLimits 占位符渲染缺失");
        assertFalse(stage2.contains("断言映射规则（动作 JSON v2 格式的 matchType"),
                "模板在场时默认契约文本位不得再出现");
        verify(coreGateway, never()).pinTemplate(anyLong(), anyLong());
    }

    @Test
    void 读路径异常降级默认生成不失败() {
        stubGenBase();
        when(coreGateway.getTaskTemplatePin(anyLong()))
                .thenThrow(new RuntimeException("core 不可达模拟"));
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        assertTrue(stage1Users.get(0).contains("请针对以下需求生成 1 条结构化测试用例："),
                "读路径异常须回退内置默认（IT-D4-01 不炸生成）");
        verify(coreGateway).createCase(eq(OPERATOR_ID), any());
    }

    
    @Test
    void 任务钉住行在场_走钉住直读不回拽桶() {
        stubGenBase();
        when(coreGateway.getTaskTemplatePin(TASK_ID)).thenReturn(9L);
        when(coreGateway.promptTemplateDetail(9L)).thenReturn(
                template(9L, PromptTemplateResolver.STAGE1_TEMPLATE_NAME, "【钉住版】需求段：{{requirement}}"));
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        assertTrue(stage1Users.get(0).contains("【钉住版】需求段：登录需求"),
                "钉住行 content 未渲染：\n" + stage1Users.get(0));
        verify(coreGateway, never()).promptTemplateLatestByStatus(
                eq(PromptTemplateResolver.STAGE1_TEMPLATE_NAME), anyString(), anyInt());
        verify(coreGateway, never()).pinTemplate(anyLong(), anyLong());
    }

    
    @Test
    void 新任务现读改后桶版本() {
        stubGenBase();
        AtomicReference<CoreGateway.PromptTemplateBrief> s1 = new AtomicReference<>(
                template(1L, PromptTemplateResolver.STAGE1_TEMPLATE_NAME, "【桶V1】需求段：{{requirement}}"));
        stubFullBucket(PromptTemplateResolver.STAGE1_TEMPLATE_NAME, s1);
        stubLlm(STAGE1_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        assertTrue(stage1Users.get(0).contains("【桶V1】"), "首轮桶选模板缺失");
        verify(coreGateway).pinTemplate(TASK_ID, 1L);

        s1.set(template(5L, PromptTemplateResolver.STAGE1_TEMPLATE_NAME, "【桶V2】需求段：{{requirement}}"));
        stage1Users.clear();
        service.generate(new AiGenMessage(TASK_ID_B, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        assertTrue(stage1Users.get(0).contains("【桶V2】"),
                "新任务须现读改后桶版本（禁缓存跨任务）");
        verify(coreGateway).pinTemplate(TASK_ID_B, 5L);
    }
}
