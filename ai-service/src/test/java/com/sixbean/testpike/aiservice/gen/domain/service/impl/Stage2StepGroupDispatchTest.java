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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * L2 for the per-group stage-2 dispatch : a case wider than
 * {@code ai.script.slice-steps} is generated group by group through the
 * UNCHANGED deliberation ring (the ring is simply called once per group), the
 * cursor inside one run advances only past converged groups so a failing group
 * re-runs alone instead of restarting the whole order, and only after every
 * group converges are the per-group DSLs merged into one script and saved.
 * Any finally-failed group fails the whole order without writing a partial
 * script, and the rejection names the group. Width 0 keeps the legacy single
 * pass byte-for-byte.
 * <p>L2 posture: CoreGateway / router / chain / event store are infrastructure
 * mocks; the slicing plan, the ring, the merge operator and the dispatch loop
 * run for real.
 */
class Stage2StepGroupDispatchTest {

    private static final long TASK_ID = 94L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 941L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final TaskProcessEventStore store = mock(TaskProcessEventStore.class);

    private AiGenServiceImpl service() {
        return new AiGenServiceImpl(
                llmChain, llmRouter, coreGateway, new ObjectMapper(),
                new CaseJsonParser(new ObjectMapper()), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(coreGateway),
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                store);
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG);
    }

    /** Five case steps, each with its own marker word, so a stub can tell which step group a stage-2 request belongs to. */
    private static final String STAGE1_JSON = """
            {"title":"登录成功","precondition":"登录页可访问",
             "steps":[{"stepNo":1,"description":"输入账号甲","isAssert":0},
                      {"stepNo":2,"description":"输入密码乙","isAssert":0},
                      {"stepNo":3,"description":"点击登录丙","isAssert":0},
                      {"stepNo":4,"description":"断言首页丁","isAssert":1},
                      {"stepNo":5,"description":"断言用户名戊","isAssert":1}],
             "expectedResult":"跳转首页","priority":0,"confidence":1,"endType":"web"}""";

    /** Schema-legal single-step DSL carrying the marker it was asked about. */
    private static String groupDsl(String marker) {
        return """
                {"version":"v2","caseId":"TC-dispatch-94","env":{"ENV":"dev","PACK":"none","DATA":"default"},
                 "setup":[{"action":"goto","description":"打开登录页%s","target":{"url":"{{ENV:BASE}}/login"}}],
                 "steps":[{"action":"wait","description":"等待控件出现%s","target":{"css":"#u","chain":["css"]},"state":"visible"}]}"""
                .formatted(marker, marker);
    }

    /** A draft that fails the action-JSON schema (no version / no env / no caseId). */
    private static final String BROKEN_DSL = "{\"steps\":[{\"action\":\"click\"}]}";

    /** User prompts of every stage-2 model pass, in call order. */
    private final List<String> stage2Prompts = new ArrayList<>();

    /**
     * Serve stage-2 requests from a marker-keyed table: a request whose prompt
     * carries the marker is answered by the matching supplier (called with how
     * many times that marker has been asked so a test can fail the first pass
     * and converge the retry). Unknown marker = test fixture bug, fails loud.
     */
    private void stubLlmByMarker(Map<String, java.util.function.IntFunction<String>> byMarker) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "dispatch", "zh-CN", 0));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-dispatch-941"));
        when(coreGateway.getSysConfig(any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            return "ai.script.slice-steps".equals(key) ? sliceStepsRaw
                    : "ai.script.slice-retry".equals(key) ? sliceRetryRaw : null;
        });
        Map<String, Integer> hits = new java.util.HashMap<>();
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String content;
                    if (r.messages().get(0).content().contains("STAGE1_CASE_JSON")) {
                        content = STAGE1_JSON;
                    } else {
                        String userPrompt = r.messages().get(1).content();
                        stage2Prompts.add(userPrompt);
                        content = answerStage2(userPrompt, hits, byMarker);
                    }
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "m", 100, 50, 20L), endpoint());
                });
    }

    private static String answerStage2(String userPrompt, Map<String, Integer> hits,
                                       Map<String, java.util.function.IntFunction<String>> table) {
        for (Map.Entry<String, java.util.function.IntFunction<String>> e : table.entrySet()) {
            if (userPrompt.contains(e.getKey())) {
                int nth = hits.merge(e.getKey(), 1, Integer::sum);
                String answer = e.getValue().apply(nth);
                // Every group prompt carries only its own steps: any OTHER
                // group's marker must be absent, else the case block leaked.
                for (String other : table.keySet()) {
                    if (!other.equals(e.getKey()) && answer.contains(other)) {
                        throw new AssertionError("组产物混入了其它组的步骤: " + other);
                    }
                }
                return answer;
            }
        }
        throw new AssertionError("阶段 2 请求未命中任何组标记: " + userPrompt);
    }

    private String sliceStepsRaw = "2";
    private String sliceRetryRaw = "1";

    private void withSlice(String stepsRaw, String retryRaw) {
        this.sliceStepsRaw = stepsRaw;
        this.sliceRetryRaw = retryRaw;
    }

    private void generate() {
        service().generate(new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID,
                1, "用户可以使用正确的账号密码登录系统", null, null));
    }

    private List<String> stepSummaries() {
        return GenWiringHarness.stepSummaries(store, TASK_ID);
    }

    private List<String> errorSummaries() {
        return GenWiringHarness.errorSummaries(store, TASK_ID);
    }

    private String savedScript() {
        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).saveScript(eq(NEW_CASE_ID), eq("v2"), saved.capture(), eq(1),
                eq(OPERATOR_ID));
        return saved.getValue();
    }

    @Test
    void 片宽两五步骤_三组各一次派发_合并一份脚本落库() throws Exception {
        withSlice("2", "1");
        stubLlmByMarker(Map.of(
                "输入账号甲", nth -> groupDsl("甲"),
                "点击登录丙", nth -> groupDsl("丙"),
                "断言用户名戊", nth -> groupDsl("戊")));

        generate();

        // One group prompt per converged group, each naming only its own steps.
        assertEquals(3, stage2Prompts.size(), "五步骤×片宽2=3 组各一次派发");
        assertTrue(stage2Prompts.get(0).contains("输入账号甲"));
        assertTrue(!stage2Prompts.get(0).contains("点击登录丙"),
                "第一组请求不得混入第三组的步骤");
        // Group milestones land on the shared process ledger .
        List<String> events = stepSummaries();
        assertTrue(events.stream().anyMatch(s -> s.contains("步骤组 1/3")),
                "第 1 组进度缺失: " + events);
        assertTrue(events.stream().anyMatch(s -> s.contains("步骤组 2/3")),
                "第 2 组进度缺失: " + events);
        assertTrue(events.stream().anyMatch(s -> s.contains("步骤组 3/3")),
                "第 3 组进度缺失: " + events);
        // One save: the merged whole carries every group's step.
        var steps = new ObjectMapper().readTree(savedScript()).path("steps");
        assertEquals(3, steps.size(), "三组各 1 步合并后 3 步");
        assertTrue(steps.get(0).path("description").asText().contains("甲"));
        assertTrue(steps.get(2).path("description").asText().contains("戊"));
    }

    @Test
    void 片宽零_整单一次派发_旧形态零漂移() {
        withSlice("0", "1");
        stubLlmByMarker(Map.of("输入账号甲", nth -> groupDsl("甲")));

        generate();

        assertEquals(1, stage2Prompts.size(), "关态=旧的一次整单派发");
        assertTrue(stage2Prompts.get(0).contains("断言用户名戊"),
                "关态请求必须带上全部步骤");
        verify(store, never()).record(any(), any(), any(), any(), contains("步骤组"), any());
    }

    @Test
    void 第二组首败_只从该组续跑_已过组禁重发_收敛后合并落库() {
        withSlice("2", "1");
        // group 2 burns its whole first ring (proposal + targeted revision both
        // broken), so the per-group retry has to re-dispatch that group alone.
        stubLlmByMarker(Map.of(
                "输入账号甲", nth -> groupDsl("甲"),
                "点击登录丙", nth -> nth <= 2 ? BROKEN_DSL : groupDsl("丙"),
                "断言用户名戊", nth -> groupDsl("戊")));

        generate();

        int group1 = (int) stage2Prompts.stream().filter(p -> p.contains("输入账号甲")).count();
        int group2 = (int) stage2Prompts.stream().filter(p -> p.contains("点击登录丙")).count();
        int group3 = (int) stage2Prompts.stream().filter(p -> p.contains("断言用户名戊")).count();
        assertEquals(1, group1, "已过组必须不再重发（游标只前进）");
        assertEquals(3, group2, "败组按 slice-retry 单独续跑：2 次首环 + 1 次续跑");
        assertEquals(1, group3, "尾组照常派发一次");
        assertTrue(stage2Prompts.indexOf(firstPromptWith("断言用户名戊"))
                        > stage2Prompts.indexOf(firstPromptWith("点击登录丙")),
                "尾组必须排在续跑之后（游标顺序）");
        assertTrue(stepSummaries().stream().anyMatch(s -> s.contains("续跑")
                        && s.contains("步骤组 2/3")),
                "续跑必须落进度面: " + stepSummaries());
        verify(coreGateway).saveScript(eq(NEW_CASE_ID), eq("v2"), any(), eq(1), eq(OPERATOR_ID));
    }

    private String firstPromptWith(String marker) {
        return stage2Prompts.stream().filter(p -> p.contains(marker)).findFirst().orElse("");
    }

    @Test
    void 第二组终败_整单不落库_拒因点名片号() {
        withSlice("2", "0");
        stubLlmByMarker(Map.of(
                "输入账号甲", nth -> groupDsl("甲"),
                "点击登录丙", nth -> BROKEN_DSL,
                "断言用户名戊", nth -> groupDsl("戊")));

        assertThrows(ServiceException.class, this::generate);

        verify(coreGateway, never()).saveScript(anyLong(), any(), any(), anyInt(), eq(OPERATOR_ID));
        assertTrue(errorSummaries().stream().anyMatch(s -> s.contains("步骤组 2/3")),
                "失败因必须点名片号: " + errorSummaries());
        // retry=0 → the failed group is not re-dispatched and the tail group never starts.
        assertTrue(stage2Prompts.stream().noneMatch(p -> p.contains("断言用户名戊")),
                "终败即停，禁继续派发后续组");
    }
}
