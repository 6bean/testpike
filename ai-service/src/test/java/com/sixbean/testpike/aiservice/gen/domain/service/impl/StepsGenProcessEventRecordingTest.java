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
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.AiGenMessage;
import com.sixbean.testpike.common.util.ActionJsonValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4 segment B (sub-package 2): the script-generation run reports its
 * milestones into the shared process-event ledger (tp_ai_task_event,
 * single source) so the job drawer can tail a long steps-gen run live —
 * one step event when the stage-2 deliberation ring finishes (rounds and
 * model calls named, converged or not), one step event when the script
 * lands in the case library, and one error event naming the rejection or
 * failure cause (residual claims / provider outage) beside every honest
 * rejection. The exception itself always propagates: the red event never
 * swallows the failure.
 * <p>Row contract: job_kind is the V460 frozen literal "steps-gen", and
 * both job_ref and run_token are the task id — the exact keys the core
 * read door (AiTaskServiceImpl) windows on, no second keying scheme.
 * <p>L2 posture: CoreGateway / router / chain / event store are the
 * infrastructure mocks; the generation pipeline, the ring and the
 * reporting hooks run for real. A legacy wiring without the store (null
 * seam) must stay a zero-touch no-op, never a crash.
 */
class StepsGenProcessEventRecordingTest {

    private static final long TASK_ID = 73L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 731L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final TaskProcessEventStore store = mock(TaskProcessEventStore.class);

    /** Full-shape assembly: real gen-domain pieces, null bridges, mock store. */
    private AiGenServiceImpl serviceWithStore() {
        return newService(store);
    }

    private AiGenServiceImpl newService(TaskProcessEventStore eventStore) {
        return new AiGenServiceImpl(
                llmChain, llmRouter, coreGateway, new ObjectMapper(),
                new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(coreGateway),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                eventStore);
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static final String STAGE1_JSON = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号密码","isAssert":0},
                      {"stepNo":2,"description":"断言首页","isAssert":1}],
             "expectedResult":"跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    /** Schema-legal but style-violating script (no descriptions + ms hard
     *  wait + fragile nth-child locator + zero assertions). */
    private static final String STAGE2_VIOLATING = """
            {"version":"v2","caseId":"TC-PROC-1","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "setup":[],
             "steps":[
               {"action":"wait","ms":5000},
               {"action":"click","target":{"css":"ul > li:nth-child(2)","chain":["css"]}}
             ]}""";

    /** Gate-passing script (Chinese step descriptions + conditional wait + assertion). */
    private static final String STAGE2_CLEAN = """
            {"version":"v2","caseId":"TC-PROC-1","env":{"ENV":"dev","PACK":"none","DATA":"default"},
             "setup":[{"action":"goto","description":"打开登录页","target":{"url":"{{ENV:BASE}}/login"}}],
             "steps":[
               {"action":"wait","description":"等待登录表单出现","target":{"css":"#username","chain":["css"]},"state":"visible"},
               {"action":"expect","description":"断言跳转首页","target":{"text":"首页"},"value":"首页"}
             ]}""";

    private final List<String> stage2Prompts = new java.util.ArrayList<>();

    private void stubBase(String gateRaw) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "proc", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-proc-731"));
        when(coreGateway.getSysConfig(any())).thenAnswer(inv ->
                "script.style-gate.enabled".equals(inv.getArgument(0)) ? gateRaw : null);
    }

    /** Stage-2 outputs served per round (last one sticky); stage-1 always answers the case JSON. */
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

    /** All error-level rows the run landed on the steps-gen face. */
    private List<String> errorSummaries() {
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).record(eq("steps-gen"), eq(String.valueOf(TASK_ID)),
                eq(String.valueOf(TASK_ID)), eq("error"), summary.capture(), any());
        return summary.getAllValues();
    }

    private void generate(AiGenServiceImpl service) {
        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                1, "用户可以使用正确的账号密码登录系统", null, null));
    }

    @Test
    void 收敛落库_step事件两枚_环结束里程碑与脚本落库_键为作业类别与任务号() {
        AiGenServiceImpl service = serviceWithStore();
        stubBase(null);
        stubLlm(STAGE2_CLEAN);

        generate(service);

        verify(store).record(eq("steps-gen"), eq(String.valueOf(TASK_ID)),
                eq(String.valueOf(TASK_ID)), eq("step"), contains("逐项核验结束"), any());
        verify(store).record(eq("steps-gen"), eq(String.valueOf(TASK_ID)),
                eq(String.valueOf(TASK_ID)), eq("step"), contains("脚本已写入"), any());
        assertTrue(stage2Prompts.size() >= 1, "脚本轮应真实发出");
        verify(coreGateway).saveScript(eq(NEW_CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
    }

    @Test
    void 未完成校验终局_error事件点名未完成核验项_里程碑注明未完成校验_异常照常上抛() {
        AiGenServiceImpl service = serviceWithStore();
        stubBase("true");
        stubLlm(STAGE2_VIOLATING);

        assertThrows(ServiceException.class, () -> generate(service));

        verify(store).record(eq("steps-gen"), eq(String.valueOf(TASK_ID)),
                eq(String.valueOf(TASK_ID)), eq("step"), contains("未完成核验"), any());
        assertTrue(errorSummaries().stream().anyMatch(s -> s.contains("未完成核验项")),
                "终局 error 事件必须点名未完成核验项: " + errorSummaries());
        verify(coreGateway, never()).saveScript(anyLong(), any(), any(), anyInt(), eq(OPERATOR_ID));
    }

    @Test
    void 服务异常_error事件点名失败因() {
        AiGenServiceImpl service = serviceWithStore();
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "proc", "zh-CN", 0));
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenThrow(new ServiceException("AI 供应商调用失败", 50201));

        assertThrows(RuntimeException.class, () -> generate(service));

        assertTrue(errorSummaries().stream().anyMatch(s -> s.contains("供应商")),
                "失败 error 事件必须点名失败因: " + errorSummaries());
    }

    @Test
    void 旧装配无事件门_照常跑不崩溃_新形状仅经末构造接入() {
        AiGenServiceImpl legacy = newService(null);
        stubBase(null);
        stubLlm(STAGE2_CLEAN);

        generate(legacy);

        verify(coreGateway).saveScript(eq(NEW_CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
        verify(store, never()).record(any(), any(), any(), any(), any(), any());
    }
}
