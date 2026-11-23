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
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillUsageRemoteInput;
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
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AiGenServiceImplSkillWiringTest {

    private static final long TASK_ID = 88L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 900L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final SkillAssemblyBridge skillBridge = new SkillAssemblyBridge(coreGateway,
            new LlmPromptAssembler(new TokenCounter()), new SkillUsageRecorder(coreGateway, mock(CtxMemoryHitWriter.class)), new AiCtxConfigResolver(coreGateway, new AiCtxProperties()));

    private final AiGenServiceImpl service = new AiGenServiceImpl(
            llmChain, llmRouter, coreGateway, new ObjectMapper(),
            new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
            new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
            new ScriptLimitResolver(coreGateway), skillBridge);

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static SkillAssemblyRemoteItem item(long id, int role, String body) {
        return new SkillAssemblyRemoteItem(id, "SK" + id, role, 5, 1, 1, body, 0);
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

    private void stubGenBase() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "reg", "zh-CN", 1));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-reg-900"));
    }

    
    private final List<String> systemMessages = new java.util.ArrayList<>();

    private void stubLlm(String... stage2Outputs) {
        int[] idx = {0};
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    systemMessages.add(system);
                    String content = system.contains("STAGE1_CASE_JSON") ? STAGE1_JSON
                            : stage2Outputs[Math.min(idx[0]++, stage2Outputs.length - 1)];
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "m", 100, 50, 20L), endpoint());
                });
    }

    @Test
    void 绑定SKILL注入stage1与stage2首轮按role路由() {
        stubGenBase();
        when(coreGateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of(
                item(21L, 3, "通用文案规范ABC"),
                item(22L, 1, "脚本定位规范XYZ")));
        stubLlm(STAGE2_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        String stage1 = systemMessages.get(0);
        assertTrue(stage1.contains("通用文案规范ABC"), "role=3 通用 SKILL 未注入 stage1");
        assertTrue(!stage1.contains("脚本定位规范XYZ"), "role=1 脚本 SKILL 误入 stage1");
        String stage2 = systemMessages.stream().filter(s -> s.contains("STAGE2_ACTION_JSON"))
                .findFirst().orElseThrow();
        assertTrue(stage2.contains("脚本定位规范XYZ"), "role=1 未注入 stage2");

        ArgumentCaptor<List<SkillUsageRemoteInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(coreGateway, timeout(3000).atLeast(2)).recordSkillUsages(cap.capture());
        List<SkillUsageRemoteInput> all = cap.getAllValues().stream()
                .flatMap(List::stream).toList();
        assertTrue(all.stream().allMatch(u -> u.taskId() == TASK_ID));
        assertTrue(all.stream().anyMatch(u -> u.skillId() == 21 && u.stage() == 2 && u.dropped() == 0));
        assertTrue(all.stream().anyMatch(u -> u.skillId() == 22 && u.stage() == 3));
        assertEquals(3, all.stream().filter(u -> u.stage() == 2 || u.stage() == 3).count(),
                "stage2 应含 21(通用)+22(脚本) 两条");
    }

    @Test
    void 校验重试轮走REPAIR_LOOP注入role2测试SKILL() {
        stubGenBase();
        when(coreGateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of(
                item(31L, 2, "失败判定修复守则QQ")));
        stubLlm("{bad json", STAGE2_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        List<String> stage2Systems = systemMessages.stream()
                .filter(s -> s.contains("STAGE2_ACTION_JSON")).toList();
        assertEquals(2, stage2Systems.size(), "应经历一次校验重试");
        assertTrue(!stage2Systems.get(0).contains("失败判定修复守则QQ"),
                "role=2 测试 SKILL 不得进首轮 SCRIPT_GEN");
        assertTrue(stage2Systems.get(1).contains("失败判定修复守则QQ"),
                "重试轮=修复语义，role=2 未注入 REPAIR_LOOP");

        ArgumentCaptor<List<SkillUsageRemoteInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(coreGateway, timeout(3000).atLeast(1)).recordSkillUsages(cap.capture());
        List<SkillUsageRemoteInput> all = cap.getAllValues().stream()
                .flatMap(List::stream).toList();
        assertTrue(all.stream().anyMatch(u -> u.skillId() == 31 && u.stage() == 4
                && u.dropped() == 0 && u.taskId() == TASK_ID), "REPAIR_LOOP 留痕 stage=4 缺失");
    }

    /**
     * Prefix-stability contract: the SKILL section is computed once per task and
     * frozen across retry rounds (the same discipline the knowledge/few-shot/
     * wording segments already keep). Re-assembling per attempt moves the system
     * prefix every round, which is exactly what kills provider-side prompt
     * caching (per-turn variables belong to the user segment, never the system).
     */
    @Test
    void 阶段1重试轮system前缀冻结装配单任务一次取数() {
        stubGenBase();
        when(coreGateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of(
                item(21L, 3, "通用文案规范ABC")));
        final List<String> stage1Systems = new java.util.ArrayList<>();
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    String content;
                    if (system.contains("STAGE1_CASE_JSON")) {
                        stage1Systems.add(system);
                        content = stage1Systems.size() == 1 ? "{bad" : STAGE1_JSON;
                    } else {
                        content = STAGE2_JSON;
                    }
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "m", 100, 50, 20L),
                            endpoint());
                });

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1,
                "登录需求", null, null));

        assertEquals(2, stage1Systems.size(), "stage1 应经历一次解析重试");
        assertEquals(stage1Systems.get(0), stage1Systems.get(1),
                "重试轮 system 必须逐字节冻结（本轮变量只进 user 段，前缀稳定性契约）");
        // CASE_GEN assembled once + SCRIPT_GEN once — retry rounds never re-fetch
        verify(coreGateway, org.mockito.Mockito.times(2))
                .getProjectAssembly(PROJECT_ID);
    }

    /**
 * segment3 (deliberation ring): a converged ring stops early — one broken
 * round plus one good round = only 2 stage2 model calls (the legacy fixed
 * three-round loop spent a third confirming call), while each prefix family
 * (SCRIPT_GEN proposal / REPAIR_LOOP revision) still assembles exactly once
 * per task — the early stop saves model calls, not assembly fetches.
 */
    @Test
    void 阶段2收敛即停环前缀每阶段单任务一次装配() {
        stubGenBase();
        when(coreGateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of(
                item(31L, 2, "失败判定修复守则QQ")));
        stubLlm("{bad json", STAGE2_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1,
                "登录需求", null, null));

        List<String> stage2Systems = systemMessages.stream()
                .filter(s -> s.contains("STAGE2_ACTION_JSON")).toList();
        assertEquals(2, stage2Systems.size(),
                "second draft already passes = the ring stops at convergence");
        assertTrue(!stage2Systems.get(0).contains("失败判定修复守则QQ"),
                "first pass rides the SCRIPT_GEN prefix");
        assertTrue(stage2Systems.get(1).contains("失败判定修复守则QQ"),
                "revision round rides the REPAIR_LOOP prefix");
        // CASE_GEN + SCRIPT_GEN + REPAIR_LOOP = one fetch per prefix family; the
        // second revision round never happens (early stop), so no extra fetch either
        verify(coreGateway, org.mockito.Mockito.times(3))
                .getProjectAssembly(PROJECT_ID);
    }

    /**
 * segment3 keeps the prefix-freeze discipline inside the ring.
 * re-adjudicates the non-convergence shape (16 §93:
 * the round ceiling is a budget, not a quota): against a provider whose
 * disputes never change, a further revision round is idle spin, so the
 * ring now stops inside the round the stall is detected — the revision
 * system stays byte-frozen per stage and the residual disputes still
 * surface in the rejection message as a plain-language list.
 */
    @Test
    void 阶段2分歧零新增提前收轮修订轮前缀逐字节冻结() {
        stubGenBase();
        // Ceiling raised to 3 (governance key read per call, hot update):
        // — a frozen dispute set must end the ring at pass 2, the extra
        // round is never burned.
        when(coreGateway.getSysConfig("ai.kernel.deliberation-rounds")).thenReturn("3");
        when(coreGateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of(
                item(31L, 2, "失败判定修复守则QQ")));
        stubLlm("{bad json");

        com.sixbean.testpike.common.exception.ServiceException ex =
                assertThrows(com.sixbean.testpike.common.exception.ServiceException.class,
                        () -> service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                                1, "登录需求", null, null)));

        List<String> stage2Systems = systemMessages.stream()
                .filter(s -> s.contains("STAGE2_ACTION_JSON")).toList();
        assertEquals(2, stage2Systems.size(),
                "AY T609 zero-new early stop: 1 proposal + 1 revision, the stalled "
                        + "third round is never burned (stub disputes repeat verbatim)");
        assertTrue(stage2Systems.get(1).contains("失败判定修复守则QQ"),
                "the revision round rides the frozen REPAIR_LOOP prefix");
        assertTrue(ex.getMessage().contains("AI 脚本格式校验失败"),
                "unconverged ring rejects honestly: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("上次脚本不合格"),
                "rejection names the residual disputes: " + ex.getMessage());
    }

    @Test
    void 无绑定prompt零改动零留痕() {
        stubGenBase();
        when(coreGateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of());
        stubLlm(STAGE2_JSON);

        service.generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1, "登录需求", null, null));

        assertTrue(systemMessages.stream().noneMatch(s -> s.contains("项目 SKILL 规范")),
                "空候选不得引入段标题（现有生成链零破坏）");
        verify(coreGateway, never()).recordSkillUsages(any());
    }
}
