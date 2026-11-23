package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolInvokeResultRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.pipeline.StallFingerprintGuard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.PROJECT_ID;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.ACTOR_ID;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.TASK_ID;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.MAPPER;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.baseRequest;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.finalAnswer;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.item;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.withTools;

/**
 * Stall-reflection wiring : the ReAct loop asks the
 * {@link StallFingerprintGuard} BEFORE dispatching each call, records the
 * outcome after it, and feeds the strategy-change frame back as the tool
 * message of a refused call — the model sees the refusal and must switch to
 * a variant query, a clarification or an honest residual answer.
 * <p>Assertion surface (kernel L2 criteria + the K8 wiring duty):
 * ① same failing call shape twice → the third identical call is refused
 * up-front (dispatch count stays 2) and the guidance frame joins the
 * transcript;
 * ② a different argument shape stays open (variant query is the point);
 * ③ an intervening success clears the streak;
 * ④ the streak bar arrives from K8 {@code ai.behavior.stall-block-attempts}
 * (hot read per run) and an invalid value falls back to the shipped 2;
 * ⑤ refused calls count as rejections in the run counters, never as
 * dispatches.
 * <p>L2 isolation: provider chain + core gateway stubbed (approved harness);
 * assembly, dispatcher, round policy, fan-out policy and the guard itself
 * stay real objects.
 */
class ReActRunnerStallReflectionTest {

    private final ReActTestHarness fx = new ReActTestHarness();
    private static final String TOOL = "query_metric";
    private static final String FAIL_MSG = "connection refused on port 8083";
    private static final String ARGS_A = "{\"q\":\"daily\"}";
    private static final String ARGS_B = "{\"q\":\"weekly\"}";

    private final AtomicInteger dispatchCount = new AtomicInteger();

    /** Runner with the guard armed through the K8 policy (fan-out off). */
    private ReActRunner runner() {
        return new ReActRunner(fx.chain,
                new LlmFunctionCalling(fx.gateway, MAPPER),
                new ToolDispatcher(fx.gateway, MAPPER),
                new FcRoundPolicy(fx.gateway),
                new AggregateQueryTool(fx.gateway, MAPPER),
                com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting.NONE,
                null, null, null,
                new StallBlockPolicy(fx.gateway));
    }

    /** One read-only tool in the plan; every invoke fails with FAIL_MSG. */
    private void stubAlwaysFailing() {
        dispatchCount.set(0);
        when(fx.gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(item(11, TOOL, 1, false)));
        when(fx.gateway.invokeTool(any(), any(), anyLong(), anyString(), isNull(), any()))
                .thenAnswer(inv -> {
                    dispatchCount.incrementAndGet();
                    return new ToolInvokeResultRemote(11L, TOOL, false, null,
                            "network", null, FAIL_MSG, 5L);
                });
    }

    /** K8 stub: round cap relaxed so the scripted rounds never hit it. */
    private void stubConfig(String stallBar) {
        when(fx.gateway.getSysConfig(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            if (FcRoundPolicy.KEY.equals(key)) {
                return "10";
            }
            if (StallFingerprintGuard.KEY_BLOCK_AFTER.equals(key)) {
                return stallBar;
            }
            return null;
        });
    }

    private static LlmToolCall call(String id, String args) {
        return new LlmToolCall(id, TOOL, args);
    }

    /** Transcript text of every role=tool message joined for keyword search. */
    private static String toolTranscript(ReActOutcome outcome) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : outcome.messages()) {
            if ("tool".equals(m.role())) {
                sb.append(m.content()).append('\n');
            }
        }
        return sb.toString();
    }

    /** ① two same-shape failures bar the third; frame names the three exits. */
    @Test
    void thirdIdenticalFailingCallIsRefusedWithStrategyFrame() {
        stubAlwaysFailing();
        stubConfig("2");
        fx.scriptChain();
        fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        fx.script.add(finalAnswer("已按新策略收尾"));

        ReActOutcome outcome = runner().run(baseRequest("查日活"), PROJECT_ID,
                null, ACTOR_ID, TASK_ID, null);

        assertEquals(2, dispatchCount.get(), "third identical call must not dispatch");
        assertTrue(toolTranscript(outcome).contains("已被系统拒绝"),
                "refusal frame must reach the model through the tool message");
        assertTrue(toolTranscript(outcome).contains("换一种方式"),
                "frame lists the variant-query exit");
        assertFalse(outcome.limitReached());
    }

    /** ② a variant argument shape stays open past the bar. */
    @Test
    void variantArgumentsAreNeverBlocked() {
        stubAlwaysFailing();
        stubConfig("2");
        fx.scriptChain();
        fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        fx.script.add(withTools(List.of(call("c1", ARGS_B))));
        fx.script.add(finalAnswer("换形重试达成"));

        ReActOutcome outcome = runner().run(baseRequest("查日活"), PROJECT_ID,
                null, ACTOR_ID, TASK_ID, null);

        assertEquals(3, dispatchCount.get(), "variant shape must still dispatch");
        assertFalse(toolTranscript(outcome).contains("已被系统拒绝"));
    }

    /** ③ an intervening success clears the streak for that shape. */
    @Test
    void successBetweenFailuresClearsTheStreak() {
        dispatchCount.set(0);
        when(fx.gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(item(11, TOOL, 1, false)));
        when(fx.gateway.invokeTool(any(), any(), anyLong(), anyString(), isNull(), any()))
                .thenAnswer(inv -> {
                    int n = dispatchCount.incrementAndGet();
                    // Second dispatch succeeds (clears the streak of one).
                    return n == 2
                            ? new ToolInvokeResultRemote(11L, TOOL, true, "{\"ok\":1}",
                                    null, null, null, 5L)
                            : new ToolInvokeResultRemote(11L, TOOL, false, null,
                                    "network", null, FAIL_MSG, 5L);
                });
        stubConfig("2");
        fx.scriptChain();
        for (int i = 0; i < 5; i++) {
            fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        }
        fx.script.add(finalAnswer("清账后终答"));

        ReActOutcome outcome = runner().run(baseRequest("查日活"), PROJECT_ID,
                null, ACTOR_ID, TASK_ID, null);

        // fail, success, fail, fail → the 5th same-shape call is barred.
        assertEquals(4, dispatchCount.get());
        assertTrue(toolTranscript(outcome).contains("已被系统拒绝"));
    }

    /** ④a the K8 hot bar is honored per run (raised to 3 allows a third try). */
    @Test
    void barReadsRaisedK8KeyPerRun() {
        // One stubbing per test method: re-stubbing the chain inside a live
        // test re-invokes the previous answer and eats a queued script entry
        // (the fan-out fixture note calls this trap out; fresh instance here).
        stubAlwaysFailing();
        stubConfig("3");
        fx.scriptChain();
        for (int i = 0; i < 4; i++) {
            fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        }
        fx.script.add(finalAnswer("bar=3 收尾"));

        ReActOutcome raised = runner().run(baseRequest("查日活"), PROJECT_ID,
                null, ACTOR_ID, TASK_ID, null);
        assertEquals(3, dispatchCount.get(), "raised bar allows a third try");
        assertTrue(toolTranscript(raised).contains("已被系统拒绝"));
    }

    /** ④b an invalid K8 value falls back to the shipped default 2. */
    @Test
    void invalidK8ValueFallsBackToDefaultBar() {
        stubAlwaysFailing();
        stubConfig("abc");
        fx.scriptChain();
        fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        fx.script.add(finalAnswer("默认 bar 收尾"));

        ReActOutcome fallback = runner().run(baseRequest("查日活"), PROJECT_ID,
                null, ACTOR_ID, TASK_ID, null);
        assertEquals(2, dispatchCount.get(), "invalid K8 value falls back to 2");
        assertTrue(toolTranscript(fallback).contains("已被系统拒绝"));
    }

    /** ⑤ refusals ride the rejection counter, never the dispatch counter. */
    @Test
    void refusedCallsCountAsRejectionsNotDispatches() {
        stubAlwaysFailing();
        stubConfig("2");
        fx.scriptChain();
        fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        fx.script.add(withTools(List.of(call("c1", ARGS_A))));
        fx.script.add(finalAnswer("计数收尾"));

        ReActOutcome outcome = runner().run(baseRequest("查日活"), PROJECT_ID,
                null, ACTOR_ID, TASK_ID, null);

        // Loop counters keep the dispatcher semantics: executed failures are
        // rejections too — the guard only ADDS one rejection row without ever
        // adding a dispatch row.
        assertEquals(0, outcome.dispatched());
        assertEquals(3, outcome.rejected());
        assertEquals(2, dispatchCount.get(), "refusal never reaches the tool");
    }
}
