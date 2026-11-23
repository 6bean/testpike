package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AiGenServiceImplStyleGateWiringTest {

    private static final long TASK_ID = 97L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 971L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);

    private final AiGenServiceImpl service = new AiGenServiceImpl(
            llmChain, llmRouter, coreGateway, new ObjectMapper(),
            new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
            new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
            new ScriptLimitResolver(coreGateway));

    private final List<String> stage2Prompts = new ArrayList<>();

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static final String STAGE1_JSON = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"断言首页","isAssert":1}],
             "expectedResult":"跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    
    private static final String STAGE2_VIOLATING = """
            {"version":"v2","caseId":"TC-STYLE-1","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "setup":[],
             "steps":[
               {"action":"wait","ms":5000},
               {"action":"click","target":{"css":"ul > li:nth-child(2)","chain":["css"]}}
             ]}""";

    
    private static final String STAGE2_CLEAN = """
            {"version":"v2","caseId":"TC-STYLE-1","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "setup":[{"action":"goto","description":"打开登录页","target":{"url":"{{ENV:BASE}}/login"}}],
             "steps":[
               {"action":"wait","description":"等待登录表单出现","target":{"css":"#username","chain":["css"]},"state":"visible"},
               {"action":"expect","description":"断言跳转首页","target":{"text":"首页"},"value":"首页"}
             ]}""";

    private void stubBase(String gateRaw) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "gate", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-gate-971"));
        when(coreGateway.getSysConfig(any())).thenAnswer(inv ->
                "script.style-gate.enabled".equals(inv.getArgument(0)) ? gateRaw : null);
    }

    
    private void stubLlm(String... stage2Outputs) {
        int[] idx = {0};
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String content;
                    if (r.messages().get(0).content().contains("STAGE1_CASE_JSON")) {
                        content = STAGE1_JSON;
                    } else {
                        stage2Prompts.add(r.messages().get(1).content());
                        content = stage2Outputs[Math.min(idx[0]++, stage2Outputs.length - 1)];
                    }
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "m", 100, 50, 20L), endpoint());
                });
    }

    @Test
    void 开关开_违规产出被点名重生成_次轮过闸落库() {
        stubBase("1");
        stubLlm(STAGE2_VIOLATING, STAGE2_CLEAN);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        verify(coreGateway).saveScript(eq(NEW_CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
        assertTrue(stage2Prompts.size() >= 2, "违规后应有重试轮提示词");
        String retry = stage2Prompts.get(1);
        assertTrue(retry.contains("硬等待") || retry.contains("毫秒"), "重试反馈未点名硬等待：\n" + retry);
        assertTrue(retry.contains("nth-child") || retry.contains("位置序号"), "重试反馈未点名脆弱定位");
        assertTrue(retry.contains("断言"), "重试反馈未点名零断言");
    }

    @Test
    void 开关开_超限仍违规_人话失败零落库() {
        stubBase("1");
        stubLlm(STAGE2_VIOLATING);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                        1, "登录需求", null, null)));
        assertTrue(ex.getMessage().contains("姿势") || ex.getMessage().contains("规范"),
                "失败提示未含规范点名：" + ex.getMessage());
        verify(coreGateway, never()).saveScript(anyLong(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void 开关缺省_存量行为零变化_违规照常落库() {
        stubBase(null);
        stubLlm(STAGE2_VIOLATING);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        verify(coreGateway).saveScript(eq(NEW_CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
    }

    @Test
    void 开关关_零姿势外呼闸读一次即直通() {
        stubBase("0");
        stubLlm(STAGE2_VIOLATING);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        verify(coreGateway).saveScript(eq(NEW_CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
    }
}
