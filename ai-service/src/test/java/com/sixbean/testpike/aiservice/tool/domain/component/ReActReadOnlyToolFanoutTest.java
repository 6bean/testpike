package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolInvokeResultRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.baseRequest;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.finalAnswer;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.item;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.withTools;

/**
 * ReAct loop dispatches several INDEPENDENT read-only tool calls of one round
 * concurrently instead of one after another, while every other route keeps the
 * legacy serial shape.
 * <p>Assertion surface:
 * ① dual-form wall clock — the same fixture run with the concurrency cap at 1
 * (serial) and at 3 (fan-out) must finish the fan-out form within
 * {@code ai.tool.fanout-target-ratio} (seeded 0.6) of the serial form;
 * ② backfill order — tool messages land in the ORIGINAL call order and every
 * tool message still pairs with the assistant tool_calls write-back;
 * ③ zero-touch — write-flagged tools and batches of one never leave the caller
 * thread (serial route, no executor hop);
 * ④ fail-soft — one throwing call still backfills in order and only counts as
 * a rejection (the loop does not break);
 * ⑤ policy reading — both keys are hot (fresh read per batch) and an invalid
 * or non-positive value falls back to the shipped default.
 * <p>L2 isolation: the provider chain and the core gateway are stubbed (same
 * approved pattern as {@code ReActRunnerTest}); the assembly, dispatcher,
 * round policy and the fan-out components themselves are real objects.
 */
class ReActReadOnlyToolFanoutTest {

    private final ReActTestHarness fx = new ReActTestHarness();
    private final CoreGateway gateway = fx.gateway;
    private final Deque<LlmChatResult> script = fx.script;
    private static final long PROJECT_ID = ReActTestHarness.PROJECT_ID;
    private static final Long TASK_ID = ReActTestHarness.TASK_ID;
    private static final Long ACTOR_ID = ReActTestHarness.ACTOR_ID;
    private static final ObjectMapper MAPPER = ReActTestHarness.MAPPER;
    /** Per-call fake latency: three of them serial = 3x, fan-out = ~1x. */
    private static final long CALL_LATENCY_MS = 180L;

    private ReActRunner runner(ToolFanoutPolicy policy, ToolDispatcher dispatcher) {
        return new ReActRunner(fx.chain, new LlmFunctionCalling(fx.gateway, ReActTestHarness.MAPPER),
                dispatcher, new FcRoundPolicy(fx.gateway),
                new AggregateQueryTool(fx.gateway, ReActTestHarness.MAPPER),
                com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting.NONE,
                null, null, new ReadOnlyToolFanout(policy));
    }






    private static List<LlmToolCall> readCalls(String... ids) {
        List<LlmToolCall> calls = new ArrayList<>();
        for (String id : ids) {
            calls.add(new LlmToolCall(id, "metric_" + id, "{\"q\":\"" + id + "\"}"));
        }
        return calls;
    }

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicReference<String> firstDispatchThread = new AtomicReference<>();

    private void stubGateway(ToolDispatcher dispatcher) {
        inFlight.set(0);
        maxInFlight.set(0);
        firstDispatchThread.set(null);
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID)).thenAnswer(inv -> List.of(
                item(11, "metric_c1", 1, false),
                item(12, "metric_c2", 1, false),
                item(13, "metric_c3", 1, false),
                item(14, "metric_c4", 5, false),
                item(21, "write_c1", 1, true),
                item(22, "write_c2", 1, true)));
        when(gateway.invokeTool(any(), any(), anyLong(), anyString(), isNull(), any()))
                .thenAnswer(inv -> {
                    long toolId = inv.getArgument(2);
                    firstDispatchThread.compareAndSet(null, Thread.currentThread().getName());
                    int now = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(CALL_LATENCY_MS);
                        if (toolId == 99L) {
                            throw new IllegalStateException("core unreachable");
                        }
                        String name = toolId == 11L ? "metric_c1"
                                : toolId == 12L ? "metric_c2"
                                : toolId == 13L ? "metric_c3" : "write";
                        return new ToolInvokeResultRemote(toolId, name, true,
                                "{\"value\":" + toolId + "}", null, null, null, CALL_LATENCY_MS);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
        when(gateway.getSysConfig(anyString())).thenReturn(null);
    }

    /** ① wall clock + ② backfill order for a three-call read-only round. */
    @Test
    void fanOutBeatsSerialWithinTargetRatioAndKeepsCallOrder() {
        ToolFanoutPolicy policy = new ToolFanoutPolicy(gateway);
        ToolDispatcher dispatcher = new ToolDispatcher(gateway, MAPPER);
        stubGateway(dispatcher);
        // One stubbing for both forms (re-stubbing would re-invoke the mock).
        fx.scriptChain();
        script.add(withTools(readCalls("c1", "c2", "c3")));
        script.add(finalAnswer("串行形态结论"));
        script.add(withTools(readCalls("c1", "c2", "c3")));
        script.add(finalAnswer("扇出形态结论"));

        // serial form: cap=1 = the legacy one-after-another route
        when(gateway.getSysConfig(ToolFanoutPolicy.KEY_MAX_PARALLEL)).thenReturn("1");
        long serialStart = System.nanoTime();
        ReActOutcome serial = runner(policy, dispatcher)
                .run(ReActTestHarness.baseRequest("查三个独立指标"), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);
        long serialMs = (System.nanoTime() - serialStart) / 1_000_000;
        assertEquals(1, maxInFlight.get(), "cap=1 must stay one-at-a-time");
        assertTrue(serialMs >= CALL_LATENCY_MS * 2,
                "serial form really paid for all three calls (measured " + serialMs + "ms)");

        // fan-out form: cap=3, same fixture and same three calls
        inFlight.set(0);
        maxInFlight.set(0);
        when(gateway.getSysConfig(ToolFanoutPolicy.KEY_MAX_PARALLEL)).thenReturn("3");
        long fanStart = System.nanoTime();
        ReActOutcome fanOut = runner(policy, dispatcher)
                .run(ReActTestHarness.baseRequest("查三个独立指标"), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);
        long fanOutMs = (System.nanoTime() - fanStart) / 1_000_000;

        assertEquals(3, maxInFlight.get(), "all three independent reads were in flight");
        double ratio = policy.targetRatio();
        // Dual-form wall clock numbers ride the surefire output so the closing
        // report can cite measured values instead of impressions.
        System.out.println("[fanout-measure] perCall=" + CALL_LATENCY_MS + "ms"
                + " serial=" + serialMs + "ms fanout=" + fanOutMs + "ms"
                + " ratioCap=" + ratio + " maxInFlight=" + maxInFlight.get());
        assertTrue(fanOutMs <= serialMs * ratio,
                "fan-out " + fanOutMs + "ms must be <= serial " + serialMs
                        + "ms x ratio " + ratio);
        assertEquals(3, fanOut.dispatched(), "every read call was dispatched");
        assertEquals(serial.messages().size(), fanOut.messages().size(),
                "both forms produce the same transcript shape");
        List<String> backfillIds = new ArrayList<>();
        for (ChatMessage message : fanOut.messages()) {
            if ("tool".equals(message.role())) {
                backfillIds.add(message.toolCallId());
            }
        }
        assertEquals(List.of("c1", "c2", "c3"), backfillIds, "backfill keeps call order");
        for (ChatMessage message : fanOut.messages()) {
            if ("tool".equals(message.role())) {
                assertNotNull(message.content(), "every call got its result back");
            }
        }
    }

    /** ③ zero-touch: executor-form rows (same core link, not read-only) never join. */
    @Test
    void executorFormRowsStayOnTheSerialRoute() {
        ToolFanoutPolicy policy = new ToolFanoutPolicy(gateway);
        ToolDispatcher dispatcher = new ToolDispatcher(gateway, MAPPER);
        stubGateway(dispatcher);
        when(gateway.getSysConfig(ToolFanoutPolicy.KEY_MAX_PARALLEL)).thenReturn("3");
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID)).thenReturn(List.of(
                item(31, "exec_c1", 2, false),
                item(32, "exec_c2", 2, false),
                item(33, "exec_c3", 2, false)));
        fx.scriptChain();
        script.add(withTools(List.of(
                new LlmToolCall("c1", "exec_c1", "{}"),
                new LlmToolCall("c2", "exec_c2", "{}"),
                new LlmToolCall("c3", "exec_c3", "{}"))));
        script.add(finalAnswer("执行形态仍串行"));

        ReActOutcome outcome = runner(policy, dispatcher)
                .run(ReActTestHarness.baseRequest("查三个独立指标"), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);

        assertEquals(3, outcome.dispatched(), "the three rows still ran");
        assertEquals(1, maxInFlight.get(), "executor rows never run two at once");
        assertEquals(Thread.currentThread().getName(), firstDispatchThread.get(),
                "non-candidate calls stay on the caller thread");
    }

    /** ③w a write-flagged round stays refused on the serial route (gate untouched). */
    @Test
    void writeFlaggedToolsAreStillRefusedAndNeverFannedOut() {
        ToolFanoutPolicy policy = new ToolFanoutPolicy(gateway);
        ToolDispatcher dispatcher = new ToolDispatcher(gateway, MAPPER);
        stubGateway(dispatcher);
        when(gateway.getSysConfig(ToolFanoutPolicy.KEY_MAX_PARALLEL)).thenReturn("3");
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID)).thenReturn(List.of(
                item(21, "write_c1", 1, true),
                item(22, "write_c2", 1, true)));
        fx.scriptChain();
        script.add(withTools(List.of(
                new LlmToolCall("c1", "write_c1", "{}"),
                new LlmToolCall("c2", "write_c2", "{}"))));
        script.add(finalAnswer("写类仍被拒"));

        ReActOutcome outcome = runner(policy, dispatcher)
                .run(ReActTestHarness.baseRequest("查三个独立指标"), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);

        assertEquals(0, outcome.dispatched(), "write calls need a confirm token");
        assertEquals(2, outcome.rejected(), "both refusals count, the loop keeps running");
        assertEquals(0, maxInFlight.get(),
                "the write route never reaches the execution link, so it cannot be fanned out");
    }

    /** ③b a single read-only call is not worth a batch hop (serial, caller thread). */
    @Test
    void singleReadOnlyCallStaysOnTheSerialRoute() {
        ToolFanoutPolicy policy = new ToolFanoutPolicy(gateway);
        ToolDispatcher dispatcher = new ToolDispatcher(gateway, MAPPER);
        stubGateway(dispatcher);
        when(gateway.getSysConfig(ToolFanoutPolicy.KEY_MAX_PARALLEL)).thenReturn("4");
        fx.scriptChain();
        script.add(withTools(readCalls("c1")));
        script.add(finalAnswer("单调用无需扇出"));

        ReActOutcome outcome = runner(policy, dispatcher)
                .run(ReActTestHarness.baseRequest("查三个独立指标"), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);

        assertEquals(1, outcome.dispatched());
        assertEquals(1, maxInFlight.get());
        assertEquals(Thread.currentThread().getName(), firstDispatchThread.get(),
                "a batch of one never hops to the executor");
    }

    /** ④ fail-soft: one throwing call still backfills in order as a rejection. */
    @Test
    void oneFailingReadDoesNotBreakTheRound() {
        ToolFanoutPolicy policy = new ToolFanoutPolicy(gateway);
        ToolDispatcher dispatcher = new ToolDispatcher(gateway, MAPPER);
        stubGateway(dispatcher);
        when(gateway.getSysConfig(ToolFanoutPolicy.KEY_MAX_PARALLEL)).thenReturn("3");
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID)).thenReturn(List.of(
                item(11, "metric_c1", 1, false),
                item(99, "metric_c2", 1, false),
                item(13, "metric_c3", 1, false)));
        fx.scriptChain();
        script.add(withTools(readCalls("c1", "c2", "c3")));
        script.add(finalAnswer("部分失败仍可续"));

        ReActOutcome outcome = runner(policy, dispatcher)
                .run(ReActTestHarness.baseRequest("查三个独立指标"), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);

        assertEquals(2, outcome.dispatched());
        assertEquals(1, outcome.rejected(), "the throwing call is a readable rejection");
        List<String> ids = new ArrayList<>();
        String failedContent = null;
        for (ChatMessage message : outcome.messages()) {
            if ("tool".equals(message.role())) {
                ids.add(message.toolCallId());
                if (!message.content().startsWith("{\"value\"")) {
                    failedContent = message.content();
                }
            }
        }
        assertEquals(List.of("c1", "c2", "c3"), ids, "failure does not reorder the backfill");
        assertTrue(failedContent != null && failedContent.contains("metric_c2"),
                "the readable refusal names the tool the model can retry");
    }

    /** ⑤ both keys are hot-read every batch and fall back on invalid shapes. */
    @Test
    void policyIsHotAndFallsBackOnInvalidValues() {
        ToolFanoutPolicy policy = new ToolFanoutPolicy(gateway);
        when(gateway.getSysConfig(ToolFanoutPolicy.KEY_MAX_PARALLEL)).thenReturn("7");
        when(gateway.getSysConfig(ToolFanoutPolicy.KEY_TARGET_RATIO)).thenReturn("0.45");
        assertEquals(7, policy.maxParallel());
        assertEquals(0.45, policy.targetRatio(), 1e-9);

        when(gateway.getSysConfig(ToolFanoutPolicy.KEY_MAX_PARALLEL)).thenReturn("0");
        when(gateway.getSysConfig(ToolFanoutPolicy.KEY_TARGET_RATIO)).thenReturn("not-a-number");
        assertEquals(ToolFanoutPolicy.DEFAULT_MAX_PARALLEL, policy.maxParallel(),
                "non-positive falls back to the shipped default");
        assertEquals(ToolFanoutPolicy.DEFAULT_TARGET_RATIO, policy.targetRatio(), 1e-9,
                "unparsable ratio falls back to the shipped default");
    }

    /**
 * ⑥ symmetric dual-arm measurement : the L3
 * window compares the serial arm against the fan-out arm on P50 wall
 * clock, so EVERY group of three or more must emit one numeric INFO line
 * on its own arm — cap=1 (serial) included. Before this, the serial arm
 * produced no numbers at all and the fan-out arm only spoke up when it
 * missed the ratio, which made the P50-vs-P50 verdict unmeasurable.
 */
    @Test
    void bothArmsEmitNumericMeasurementLines() {
        ch.qos.logback.classic.Logger fanoutLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(ReadOnlyToolFanout.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> probe =
                new ch.qos.logback.core.read.ListAppender<>();
        probe.start();
        fanoutLogger.addAppender(probe);
        try {
            ToolFanoutPolicy policy = new ToolFanoutPolicy(gateway);
            ToolDispatcher dispatcher = new ToolDispatcher(gateway, MAPPER);
            stubGateway(dispatcher);
            fx.scriptChain();
            script.add(withTools(readCalls("c1", "c2", "c3")));
            script.add(finalAnswer("串行臂"));
            script.add(withTools(readCalls("c1", "c2", "c3")));
            script.add(finalAnswer("扇出臂"));

            String serialLine = runArm(policy, dispatcher, "1", probe, "臂=serial");
            String fanoutLine = runArm(policy, dispatcher, "3", probe, "臂=fanout");

            long serialWall = parseNumber(serialLine, "墙钟=");
            long serialSum = parseNumber(serialLine, "串行合计=");
            long fanoutWall = parseNumber(fanoutLine, "墙钟=");
            long fanoutSum = parseNumber(fanoutLine, "串行合计=");
            assertTrue(serialWall >= CALL_LATENCY_MS * 2,
                    "serial arm really paid every call (wall " + serialWall + "ms)");
            assertTrue(serialSum >= CALL_LATENCY_MS * 2,
                    "serial arm reports a summed per-call time (sum " + serialSum + "ms)");
            assertTrue(fanoutWall <= fanoutSum,
                    "fan-out arm wall clock is never larger than its own serial sum");
            assertTrue(fanoutWall < serialWall,
                    "fan-out arm is faster than the serial arm on the same fixture "
                            + "(fanout " + fanoutWall + "ms vs serial " + serialWall + "ms)");
            parseNumber(serialLine, "最慢单次=");
            parseNumber(fanoutLine, "比值=");
            assertTrue(serialLine.contains("组=3") && fanoutLine.contains("组=3"),
                    "both lines carry the group size the window aggregates on");
            System.out.println("[fanout-arm-measure] serial: " + serialLine
                    + " | fanout: " + fanoutLine);
        } finally {
            probe.stop();
            fanoutLogger.detachAppender(probe);
        }
    }

    /** One measured run at the given cap; returns that arm's numeric INFO line. */
    private String runArm(ToolFanoutPolicy policy, ToolDispatcher dispatcher, String cap,
                          ch.qos.logback.core.read.ListAppender<
                                  ch.qos.logback.classic.spi.ILoggingEvent> probe,
                          String armTag) {
        probe.list.clear();
        when(gateway.getSysConfig(ToolFanoutPolicy.KEY_MAX_PARALLEL)).thenReturn(cap);
        inFlight.set(0);
        maxInFlight.set(0);
        runner(policy, dispatcher)
                .run(ReActTestHarness.baseRequest("查三个独立指标"), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);
        List<String> lines = new ArrayList<>();
        for (ch.qos.logback.classic.spi.ILoggingEvent event : probe.list) {
            String text = event.getFormattedMessage();
            if (event.getLevel() == ch.qos.logback.classic.Level.INFO
                    && text.contains(armTag) && text.contains("组=3")) {
                lines.add(text);
            }
        }
        assertEquals(1, lines.size(), "exactly one measurement line for " + armTag);
        return lines.get(0);
    }

    private static long parseNumber(String line, String key) {
        int start = line.indexOf(key);
        assertTrue(start >= 0, "measurement line must carry " + key + " in: " + line);
        int end = start + key.length();
        while (end < line.length() && Character.isDigit(line.charAt(end))) {
            end++;
        }
        assertTrue(end > start + key.length(), key + " must be followed by a number: " + line);
        return Long.parseLong(line.substring(start + key.length(), end));
    }
}
