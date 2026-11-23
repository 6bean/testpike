package com.sixbean.testpike.aiservice.explore.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreEvidenceGateway;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreSessionBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.AiCallLogWriter;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import com.sixbean.testpike.aiservice.tool.domain.component.LlmFunctionCalling;
import com.sixbean.testpike.aiservice.tool.domain.component.ToolCallOutcome;
import com.sixbean.testpike.aiservice.tool.domain.component.ToolDispatcher;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * segment P1 — the exploration loop reports its fine-grained progress into
 * the shared process event ledger (tp_ai_task_event), so a multi-minute
 * autonomous exploration run is tail-readable like a live log instead of a
 * black box: one start line, one thinking line per provider round carrying the
 * visible output digest plus token counts, one step line per executed action,
 * and one exit line naming the terminal verdict (goal reached / budget cut /
 * stalled). A budget refuse lands an error line because it IS the reason the
 * run stopped early.
 * <p>L2 isolation on the infrastructure doors (CoreGateway / LlmFallbackChain /
 * ProjectLlmRouter / ToolDispatcher / evidence gateway / event store) — the
 * loop kernel and its reporting hooks run for real. Keys mirror the frozen
 * read-side convention: job_kind = "explore", job_ref = run_token = session id
 * (one row set per session, same posture as steps-gen). An absent store
 * (legacy wiring without the seam) stays a zero-touch no-op.
 */
class ExploreProcessEventRecordingTest {

    private static final long SESSION_ID = 771L;
    private static final long PROJECT_ID = 35L;
    private static final long ACTOR_ID = 7L;
    private static final String ANCHOR = String.valueOf(SESSION_ID);

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmFallbackChain chain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter router = mock(ProjectLlmRouter.class);
    private final LlmFunctionCalling functionCalling = mock(LlmFunctionCalling.class);
    private final ToolDispatcher dispatcher = mock(ToolDispatcher.class);
    private final ExploreEvidenceGateway evidenceGateway = mock(ExploreEvidenceGateway.class);
    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final AiCallLogWriter aiCallLog = mock(AiCallLogWriter.class);
    private final TaskProcessEventStore store = mock(TaskProcessEventStore.class);
    private final Deque<LlmChatResult> script = new ArrayDeque<>();

    private ExploreLoopKernel kernel(LoopContextFitting fitting) {
        when(router.route(anyLong())).thenReturn(new LlmEndpointConfig(
                "openai", "explore-test-model", "k", "http://provider.invalid",
                "test", null, null, null, null, null));
        when(functionCalling.assemblePlan(anyLong(), any())).thenReturn(FcPlan.EMPTY);
        when(functionCalling.decorate(any(ChatCompletionRequest.class), any(FcPlan.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(chain.chat(any(), anyLong(), any())).thenAnswer(inv -> script.poll());
        when(evidenceGateway.uploadEvidence(anyLong(), any(), any(), any(), any()))
                .thenReturn("dom-key-1");
        ExploreLoopKernel k = new ExploreLoopKernel(chain, router,
                new ExploreBuiltinToolCatalog(mapper), functionCalling, dispatcher,
                evidenceGateway, coreGateway, aiCallLog, mapper, fitting, null);
        k.setProcessEvents(store);
        return k;
    }

    private ExploreSessionBrief webSession() {
        return new ExploreSessionBrief(SESSION_ID, PROJECT_ID, "验证登录页可用",
                "https://shop.example.com/login", 1, 0, 0, null, null, ACTOR_ID, null);
    }

    /** All (level, summary, detail) triples the run landed for this session. */
    private List<String[]> landedRows() {
        ArgumentCaptor<String> level = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(store, atLeastOnce()).record(eq("explore"), eq(ANCHOR), eq(ANCHOR),
                level.capture(), summary.capture(), detail.capture());
        return java.util.stream.IntStream.range(0, level.getAllValues().size())
                .mapToObj(i -> new String[]{level.getAllValues().get(i),
                        summary.getAllValues().get(i), detail.getAllValues().get(i)})
                .toList();
    }

    @Test
    void 无动作轮跑完_启动思考出口三类过程事件齐落() {
        script.add(new LlmChatResult("页面快照已读取，当前无可行动作", "openai",
                "explore-test-model", 120, 30, 900L));

        kernel(LoopContextFitting.NONE).run(webSession(), ACTOR_ID);

        List<String[]> rows = landedRows();
        assertTrue(rows.stream().anyMatch(r -> "step".equals(r[0]) && r[1].contains("探索")),
                "运行开始应留下一条可读的启动事件: " + rows);
        assertTrue(rows.stream().anyMatch(r -> "thinking".equals(r[0])
                        && r[1].contains("页面快照已读取")),
                "每轮模型可见输出应留下一条 thinking 事件: " + rows);
        assertTrue(rows.stream().anyMatch(r -> "thinking".equals(r[0])
                        && r[2] != null && r[2].contains("promptTokens")
                        && r[2].contains("completionTokens")),
                "thinking 事件机器面携带 token 计数: " + rows);
        assertTrue(rows.stream().anyMatch(r -> "step".equals(r[0])
                        && (r[1].contains("出口") || r[1].contains("结束"))),
                "循环出口应留下一条判型事件: " + rows);
    }

    @Test
    void 执行动作轮_每步落step事件点名动作与成败() {
        script.add(new LlmChatResult("先调用登录取数工具", "openai", "explore-test-model",
                100, 20, 500L, List.of(new LlmToolCall("c1", "login-tool", "{}")), 0));
        script.add(new LlmChatResult("目标已达成 GOAL_REACHED", "openai", "explore-test-model",
                110, 25, 600L));
        when(dispatcher.dispatch(any(), any(), anyLong(), any(), any(), any(), anyLong()))
                .thenReturn(ToolCallOutcome.ok("login-tool", "{\"token\":\"x\"}"));

        kernel(LoopContextFitting.NONE).run(webSession(), ACTOR_ID);

        List<String[]> rows = landedRows();
        assertTrue(rows.stream().anyMatch(r -> "step".equals(r[0])
                        && r[1].contains("login-tool") && r[1].contains("成功")),
                "执行的动作应留下一条点名成败的 step 事件: " + rows);
        assertTrue(rows.stream().anyMatch(r -> "step".equals(r[0])
                        && r[1].contains("目标达成")),
                "达成出口判型应上屏: " + rows);
    }

    @Test
    void 上下文预算拒止_落error事件点名停止原因() {
        LoopContextFitting refuse = (request, projectId) -> {
            throw new ServiceException("上下文超过模型上限，无法继续装配",
                    BizErrorCode.AI_PROMPT_TOO_LARGE);
        };
        kernel(refuse).run(webSession(), ACTOR_ID);

        List<String[]> rows = landedRows();
        assertTrue(rows.stream().anyMatch(r -> "error".equals(r[0])
                        && r[1].contains("预算")),
                "预算拒止必须作为错误事件出现在过程流里: " + rows);
    }

    @Test
    void 留证门缺失未接线_跑完零抛出() {
        script.add(new LlmChatResult("页面快照已读取", "openai",
                "explore-test-model", 80, 10, 400L));
        ExploreLoopKernel k = kernel(LoopContextFitting.NONE);
        k.setProcessEvents(null);

        assertDoesNotThrow(() -> k.run(webSession(), ACTOR_ID));
    }
}
