package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * a rejection NEVER bypasses it, each repair round re-submits through the SAME
 * door; the rejection text feeds the existing stage-2 revision machinery
 * (REPAIR_LOOP system head), bounded by ai.repair.rounds (K8 hot read, same
 * parser as the fold-back cap; 0 = legacy single-attempt behaviour). Every
 * repair round is a milestone on the shared process ledger (steps-gen,
 * recordProcessEvent single source).
 * <p>L2 posture: CoreGateway / router / chain / event store are infrastructure
 * mocks; the ring, the rejection→feedback dispatch and the round budget run
 * for real.
 */
class SaveRejectedValidationRepairTest {

    private static final long TASK_ID = 95L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 951L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final TaskProcessEventStore store = mock(TaskProcessEventStore.class);

    private final List<String> stage2Prompts = new ArrayList<>();
    private String repairRoundsRaw = "3";

    private AiGenServiceImpl service() {
        return GenWiringHarness.assembleWithLedger(coreGateway, llmChain, llmRouter, store);
    }

    private static final String STAGE1_JSON = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号","isAssert":0},
                      {"stepNo":2,"description":"点击登录","isAssert":0}],
             "expectedResult":"跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    /** Schema-legal whole-case DSL (passes the ai-service-side validator too). */
    private static final String STAGE2_CLEAN = """
            {"version":"v2","caseId":"TC-repair-951","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "setup":[{"action":"goto","description":"打开登录页","target":{"url":"{{ENV:BASE}}/login"}}],
             "steps":[{"action":"wait","description":"等待登录表单出现","target":{"css":"#username","chain":["css"]},"state":"visible"},
                      {"action":"expect","description":"断言跳转首页","target":{"text":"首页"},"value":"首页"}]}""";

    private void stubLlm() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(GenWiringHarness.ENDPOINT);
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "repair", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-repair-951"));
        when(coreGateway.getSysConfig(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return "ai.repair.rounds".equals(key) ? repairRoundsRaw : null;
        });
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String content = r.messages().get(0).content().contains("STAGE1_CASE_JSON")
                            ? STAGE1_JSON : STAGE2_CLEAN;
                    if (!r.messages().get(0).content().contains("STAGE1_CASE_JSON")) {
                        stage2Prompts.add(r.messages().get(1).content());
                    }
                    return GenWiringHarness.handle(content);
                });
    }

    private void generate() {
        service().generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                1, "用户可以使用正确的账号密码登录系统", null, null));
    }

    private void rejectionOnEverySave() {
        doThrow(new ServiceException("脚本静态校验未通过：第 2 步 target.chain 不合法",
                BizErrorCode.AI_FORMAT_ERROR))
                .when(coreGateway).saveScript(anyLong(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void 落库被校验拒_错误文本回灌修复轮_修复稿经同一门再落库成功() {
        stubLlm();
        doThrow(new ServiceException("脚本静态校验未通过：第 2 步 target.chain 不合法",
                BizErrorCode.AI_FORMAT_ERROR))
                .doNothing()
                .when(coreGateway).saveScript(anyLong(), any(), any(), anyInt(), anyLong());

        generate();

        // Second save through the SAME door (gate never bypassed).
        verify(coreGateway, times(2)).saveScript(eq(NEW_CASE_ID), eq("v2"), any(), eq(1),
                eq(OPERATOR_ID));
        // The repair round is a stage-2 model pass carrying the validator text.
        assertTrue(stage2Prompts.size() >= 2, "修复轮应追加一次提案请求，实到 " + stage2Prompts.size());
        assertTrue(stage2Prompts.get(1).contains("target.chain 不合法"),
                "校验器错误文本未回灌进修复提案: " + stage2Prompts.get(1));
        List<String> events = GenWiringHarness.stepSummaries(store, TASK_ID);
        assertTrue(events.stream().anyMatch(s -> s.contains("修复轮")),
                "修复轮进度未上账本: " + events);
        assertTrue(events.stream().anyMatch(s -> s.contains("AI 脚本已写入用例")),
                "成功里程碑缺失: " + events);
    }

    @Test
    void 修复轮耗尽_整单失败转人工且残余错误点名() {
        stubLlm();
        repairRoundsRaw = "1";
        rejectionOnEverySave();

        ServiceException thrown = assertThrows(ServiceException.class, this::generate);

        // cap 1 = first save + one repaired re-save, both rejected, then to human.
        verify(coreGateway, times(2)).saveScript(anyLong(), any(), any(), anyInt(), anyLong());
        assertTrue(thrown.getMessage().contains("target.chain 不合法"),
                "终态错误未点名残余校验问题: " + thrown.getMessage());
        List<String> errors = GenWiringHarness.errorSummaries(store, TASK_ID);
        assertTrue(errors.stream().anyMatch(s -> s.contains("修复轮")),
                "耗尽事件未上账本: " + errors);
    }

    @Test
    void 轮次键为0_关态保持旧行为_一次落库零修复请求() {
        stubLlm();
        repairRoundsRaw = "0";
        rejectionOnEverySave();

        assertThrows(ServiceException.class, this::generate);

        verify(coreGateway, times(1)).saveScript(anyLong(), any(), any(), anyInt(), anyLong());
        assertEquals(1, stage2Prompts.size(), "关态不得追加修复提案请求");
    }
}
