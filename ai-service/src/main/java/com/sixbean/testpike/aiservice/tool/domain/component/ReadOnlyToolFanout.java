package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Read-only tool fan-out (behavior-kernel tenet 10 / ): one ReAct round may
 * ask for several INDEPENDENT lookups (project stats + knowledge search +
 * analysis function). Sequentially that is the sum of every call's latency;
 * here those calls run at the same time so the round pays the slowest one.
 * <p>Eligibility is deliberately narrow — the intersection of the HTTP read
 * door and the analysis door: {@code execKind=1} without the write flag, plus
 * {@code execKind=5}. Everything else keeps the serial route byte-identical:
 * write-flagged tools (they need a confirm token and a human), executor and
 * GENKIT rows, MCP bridge calls (their task bag owns a shared budget ledger
 * that is not built for concurrency) and every dialog built-in (the plan card,
 * clarify, aggregate, catalog expansion mutate the round itself).
 * <p>Order is a contract, not an optimization: the returned outcomes are in the
 * SAME order as the submitted calls, so the loop can backfill
 * {@code role=tool} messages by the original {@code tool_call_id} sequence and
 * the provider sees exactly the pairing it emitted.
 * <p>Concurrency comes from the K8 cap ({@link ToolFanoutPolicy#maxParallel},
 * hot-read per round) enforced by a per-round semaphore over virtual-thread
 * tasks (blocking IO, no pinned platform pool). Cap 1 = fan-out off: the batch
 * runs in place on the caller thread, which is the operator's kill switch
 * without a redeploy.
 * <p>Faults never escape: a throwing call becomes a readable rejected outcome
 * (dispatcher fail-soft baseline). Measurement is symmetric on both arms: any
 * group of three or more writes one numeric INFO line (arm, size, wall, serial
 * sum, slowest call, ratio) — cap=1 serial included — so the acceptance window
 * can compare P50 serial against P50 fan-out instead of guessing; a fan-out
 * group that misses the target ratio additionally logs a warning naming the
 * two tuning keys.
 */
@Component
public class ReadOnlyToolFanout {

    private static final Logger log = LoggerFactory.getLogger(ReadOnlyToolFanout.class);

    /** Groups of at least this size are compared against the target ratio. */
    private static final int MEASURABLE_GROUP = 3;

    /** Measurement arm tag: in-place execution (cap=1 or a non-eligible route). */
    static final String ARM_SERIAL = "serial";

    /** Measurement arm tag: concurrent fan-out dispatch. */
    static final String ARM_FANOUT = "fanout";

    private final ToolFanoutPolicy policy;
    private final ExecutorService tasks;

    public ReadOnlyToolFanout(ToolFanoutPolicy policy) {
        this.policy = policy;
        this.tasks = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("tool-fanout-", 0).factory());
    }

    /**
     * Is this call a read-only fan-out candidate? The routing fact comes from
     * the assembly index (the dispatcher's own authority), never from the tool
     * name — a hallucinated or write-flagged row is not eligible.
     */
    public boolean isCandidate(FcPlan plan, LlmToolCall call) {
        if (plan == null || call == null) {
            return false;
        }
        ToolFcAssemblyRemoteItem item = plan.index().get(call.name());
        if (item == null || item.write()) {
            return false;
        }
        Integer kind = item.execKind();
        return kind != null
                && (kind == ToolDispatcher.KIND_HTTP || kind == ToolDispatcher.KIND_ANALYSIS);
    }

    /** Current cap, fresh from K8 (1 = serial, the fan-out kill switch). */
    public int maxParallel() {
        return policy.maxParallel();
    }

    /**
     * Run one group of read-only calls.
     *
     * @param calls   the group, in the order the model emitted them
     * @param invoker the single-call execution (caller keeps its routing)
     * @return outcomes in the SAME order as {@code calls}
     */
    public List<ToolCallOutcome> dispatch(List<LlmToolCall> calls,
                                          Function<LlmToolCall, ToolCallOutcome> invoker) {
        if (calls == null || calls.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(1, maxParallel());
        if (calls.size() == 1 || cap == 1) {
            return serial(calls, invoker);
        }
        Semaphore slots = new Semaphore(Math.min(cap, calls.size()));
        AtomicLong slowestMs = new AtomicLong();
        AtomicLong sumMs = new AtomicLong();
        List<Future<ToolCallOutcome>> futures = new ArrayList<>(calls.size());
        long groupStart = System.nanoTime();
        for (LlmToolCall call : calls) {
            futures.add(tasks.submit(() -> {
                long started = System.nanoTime();
                slots.acquire();
                try {
                    return invoke(call, invoker);
                } finally {
                    slots.release();
                    long elapsed = (System.nanoTime() - started) / 1_000_000;
                    slowestMs.accumulateAndGet(elapsed, Math::max);
                    sumMs.addAndGet(elapsed);
                }
            }));
        }
        List<ToolCallOutcome> outcomes = new ArrayList<>(calls.size());
        long joinCapMs = policy.joinCapSeconds() * 1000L;
        for (int i = 0; i < futures.size(); i++) {
            outcomes.add(join(calls.get(i), futures.get(i), joinCapMs));
        }
        observe(calls.size(), ARM_FANOUT, (System.nanoTime() - groupStart) / 1_000_000,
                sumMs.get(), slowestMs.get());
        return outcomes;
    }

    /**
     * In-place execution — the serial route, used for a lone call or cap 1.
     * Timed like the fan-out arm so the measurement lines on both arms are
     * directly comparable (same fields, same group-size gate).
     */
    private List<ToolCallOutcome> serial(List<LlmToolCall> calls,
                                         Function<LlmToolCall, ToolCallOutcome> invoker) {
        List<ToolCallOutcome> outcomes = new ArrayList<>(calls.size());
        long groupStart = System.nanoTime();
        long sumMs = 0;
        long slowestMs = 0;
        for (LlmToolCall call : calls) {
            long started = System.nanoTime();
            outcomes.add(invoke(call, invoker));
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            sumMs += elapsed;
            slowestMs = Math.max(slowestMs, elapsed);
        }
        observe(calls.size(), ARM_SERIAL, (System.nanoTime() - groupStart) / 1_000_000,
                sumMs, slowestMs);
        return outcomes;
    }

    /** One call, fail-soft: a throw becomes a readable rejection, never a break. */
    private static ToolCallOutcome invoke(LlmToolCall call,
                                          Function<LlmToolCall, ToolCallOutcome> invoker) {
        try {
            ToolCallOutcome outcome = invoker.apply(call);
            return outcome == null
                    ? ToolCallOutcome.fail(call.name(),
                            "工具「" + call.name() + "」未返回结果（服务端结果为空，已拒绝采信）")
                    : outcome;
        } catch (RuntimeException fault) {
            log.warn("工具调用异常 tool={}: {}", call.name(), fault.getMessage());
            return ToolCallOutcome.fail(call.name(),
                    "工具「" + call.name() + "」执行失败：" + fault.getMessage());
        }
    }

    /**
     * One fan-out join, watchdog-bounded (defect A): a call still running past
     * the hot-reloadable cap is cancelled and surfaces as a readable rejection
     * naming the tool, the bound and the tuning key — never a silent hang.
     */
    private ToolCallOutcome join(LlmToolCall call, Future<ToolCallOutcome> future,
                                 long capMs) {
        try {
            return future.get(capMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException tooSlow) {
            future.cancel(true);
            log.warn("只读工具扇出等待超上限 tool={} cap={}ms（参数 {}）",
                    call.name(), capMs, ToolFanoutPolicy.KEY_JOIN_CAP);
            return ToolCallOutcome.fail(call.name(),
                    "工具「" + call.name() + "」超过等待上限 " + (capMs / 1000)
                            + " 秒未返回，本次已取消（其余查询结果不受影响）；"
                            + "该查询确实需要更久时可在系统设置调大等待上限"
                            + "（参数名称 " + ToolFanoutPolicy.KEY_JOIN_CAP + "，修改即时生效）");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolCallOutcome.fail(call.name(),
                    "工具「" + call.name() + "」并发派发被中断（已拒绝执行）");
        } catch (java.util.concurrent.ExecutionException fault) {
            Throwable cause = fault.getCause() == null ? fault : fault.getCause();
            log.warn("工具并发派发失败 tool={}: {}", call.name(), cause.getMessage());
            return ToolCallOutcome.fail(call.name(),
                    "工具「" + call.name() + "」执行失败：" + cause.getMessage());
        }
    }

    /**
     * Symmetric measurement: every group of three or more writes one numeric
     * INFO line on its own arm (both arms feed the P50-vs-P50 acceptance
     * window). The warning is fan-out-only — the serial arm's ratio is ≈1 by
     * construction, warning on it would spam every kill-switch run.
     */
    private void observe(int groupSize, String arm, long wallMs,
                         long serialSumMs, long slowestMs) {
        if (groupSize < MEASURABLE_GROUP || serialSumMs <= 0) {
            return;
        }
        double ratio = (double) wallMs / serialSumMs;
        double target = policy.targetRatio();
        log.info("只读工具扇出测量：臂={} 组={} 墙钟={}ms 串行合计={}ms 最慢单次={}ms 比值={} 目标上限={}",
                arm, groupSize, wallMs, serialSumMs, slowestMs,
                String.format("%.2f", ratio), target);
        if (ARM_FANOUT.equals(arm) && ratio > target) {
            log.warn("只读工具扇出未达预期收益：{} 项并发耗时 {}ms（串行合计 {}ms，实测比 {}，"
                            + "目标上限 {}），并发上限与上限比例可在系统设置热更（参数名称 {}、{}）",
                    groupSize, wallMs, serialSumMs, String.format("%.2f", ratio), target,
                    ToolFanoutPolicy.KEY_MAX_PARALLEL, ToolFanoutPolicy.KEY_TARGET_RATIO);
        }
    }

    @PreDestroy
    void shutdown() {
        tasks.shutdownNow();
        try {
            tasks.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
