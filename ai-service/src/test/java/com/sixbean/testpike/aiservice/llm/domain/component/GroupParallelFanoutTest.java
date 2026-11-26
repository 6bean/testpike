package com.sixbean.testpike.aiservice.llm.domain.component;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * contract of the single-source bounded fan-out pool that
 * covers the three group loops (doc main-judgement groups, spec contract
 * groups, judge question-generation groups) and the existing final-bucket
 * plane. The K8 key range (default 3, hard cap 3, never raised), task-order
 * stability under parallel execution and the fail-closed unwrapped exception
 */
class GroupParallelFanoutTest {

    // ------------------------------------------------ K8 value-range face

    @Test
    void blankOrGarbageConfigFallsBackToDefaultThree() {
        assertEquals(3, GroupParallelFanout.parallelismOf(null));
        assertEquals(3, GroupParallelFanout.parallelismOf(""));
        assertEquals(3, GroupParallelFanout.parallelismOf("   "));
        assertEquals(3, GroupParallelFanout.parallelismOf("abc"));
    }

    @Test
    void valuesAboveHardCapClampToThreeNeverRaise() {
        assertEquals(3, GroupParallelFanout.parallelismOf("4"));
        assertEquals(3, GroupParallelFanout.parallelismOf("9"));
        assertEquals(3, GroupParallelFanout.parallelismOf("1000"));
    }

    @Test
    void lowerValuesAreAcceptedAndNonPositiveClampsToOne() {
        assertEquals(1, GroupParallelFanout.parallelismOf("1"));
        assertEquals(2, GroupParallelFanout.parallelismOf("2"));
        assertEquals(1, GroupParallelFanout.parallelismOf("0"));
        assertEquals(1, GroupParallelFanout.parallelismOf("-5"));
    }

    @Test
    void hotKeyIsCrossDomainPipelineNamespaceNotDocNamespace() {
        // ai.doc.* would hide the cross-domain face from the config audit.
        assertEquals("ai.pipeline.group-parallel", GroupParallelFanout.KEY_GROUP_PARALLEL);
    }

    // ------------------------------------------------ order-stability face

    @Test
    void resultsKeepTaskOrderUnderParallelExecution() throws Exception {
        // Later tasks finish sooner: every task sleeps proportionally LESS
        // than its predecessor, so completion order is the reverse of the
        // submission order while the returned list must stay in task order.
        int n = 6;
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int index = i;
            int holdMs = (n - i) * 30;
            tasks.add(() -> {
                Thread.sleep(holdMs);
                return index;
            });
        }
        List<Integer> results = GroupParallelFanout.runAll(tasks, 3, "t607-order");
        assertEquals(List.of(0, 1, 2, 3, 4, 5), results);
    }

    @Test
    void sameTasksUnderParallelOneAndThreeProduceEqualResults() throws Exception {
        // Dual-spectrum baseline: the merged downstream (idempotent title
        // fold) consumes this list in order, so parallel=1 and parallel=3
        // over deterministic tasks must be element-wise identical.
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            int index = i;
            tasks.add(() -> "item-" + index);
        }
        List<String> serial = GroupParallelFanout.runAll(tasks, 1, "t607-dual-1");
        List<String> parallel = GroupParallelFanout.runAll(tasks, 3, "t607-dual-3");
        assertEquals(serial, parallel);
        assertEquals(9, parallel.size());
    }

    @Test
    void parallelOneRunsEveryTaskOnTheCallerThread() {
        String callerThread = Thread.currentThread().getName();
        List<Callable<String>> tasks = List.of(
                () -> Thread.currentThread().getName(),
                () -> Thread.currentThread().getName());
        List<String> threads = GroupParallelFanout.runAll(tasks, 1, "t607-serial");
        assertEquals(List.of(callerThread, callerThread), threads);
    }

    // ------------------------------------------------ fail-closed face

    @Test
    void runtimeFailurePropagatesTheSameExceptionUnwrapped() throws Exception {
        IllegalStateException abort = new IllegalStateException("generation-abort-vocabulary");
        CountDownLatch never = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        tasks.add(() -> {
            throw abort;
        });
        tasks.add(() -> {
            // A still-blocked sibling must not keep runAll alive after the
            // first failure: shutdownNow interrupts it and the caller has
            // already seen the abort above.
            never.await(10, TimeUnit.SECONDS);
            return "late";
        });
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> GroupParallelFanout.runAll(tasks, 3, "t607-fail"));
        assertSame(abort, thrown);
    }

    @Test
    void checkedFailureIsWrappedOnceKeepingTheCauseReachable() {
        List<Callable<String>> tasks = List.of(() -> {
            throw new Exception("checked-surface");
        });
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> GroupParallelFanout.runAll(tasks, 2, "t607-checked"));
        assertInstanceOf(Exception.class, thrown.getCause());
        assertEquals("checked-surface", thrown.getCause().getMessage());
    }

    @Test
    void emptyTaskListReturnsEmptyListWithoutSpinningUpPool() {
        List<String> results = GroupParallelFanout.runAll(List.of(), 3, "t607-empty");
        assertTrue(results.isEmpty());
    }

    @Test
    void poolWorkersAreDaemonNamedAndReleasedAfterSuccess() throws Exception {
        // Only runs that actually reach the pool carry worker identity: a
        // single task short-circuits inline (serial baseline contract), so
        // two tasks over parallel=2 prove the naming and daemon flags.
        List<Callable<Thread>> tasks = List.of(
                Thread::currentThread,
                Thread::currentThread);
        List<Thread> workers = GroupParallelFanout.runAll(tasks, 2, "t607-named");
        assertEquals(2, workers.size());
        for (Thread worker : workers) {
            assertTrue(worker.getName().startsWith("t607-named"),
                    "worker threads must carry the caller-supplied prefix, got " + worker.getName());
            assertTrue(worker.isDaemon(), "pool workers must be daemon threads");
        }
    }

    // ------------------------------------------- budget-abort face
    // Measure 1 (propagate the gate trip to in-flight siblings) + measure 2
    // (pre-call gate check): once the run-level budget signal is tripped,
    // unstarted tasks must never launch (provider-call increment after the
    // trip stays exactly 0), in-flight siblings are interrupted, and the
    // caller stops waiting within one poll window instead of riding every
    // sibling to its natural end (goldfinal3 live evidence: 35 groups kept
    // burning ~2h past the INCOMPLETE verdict).

    @Test
    void abortTrippedBeforeRunLaunchesNoTaskAtAll() {
        RunBudgetAbort abort = new RunBudgetAbort();
        abort.trip("budget-gate-tripped-before-launch");
        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        List<Callable<String>> tasks = List.of(
                () -> {
                    calls.incrementAndGet();
                    return "a";
                },
                () -> {
                    calls.incrementAndGet();
                    return "b";
                },
                () -> {
                    calls.incrementAndGet();
                    return "c";
                });
        GroupFanoutAbortException thrown = assertThrows(GroupFanoutAbortException.class,
                () -> GroupParallelFanout.runAll(tasks, 3, "t641-pre", abort));
        assertEquals(0, calls.get(), "闸后新调用必须恒 0，不是趋近 0");
        List<String> landed = thrown.landedInOrder();
        assertEquals(3, landed.size());
        assertTrue(landed.stream().allMatch(java.util.Objects::isNull),
                "未启动槽位必须以 null 如实呈现");
        assertTrue(thrown.getMessage().contains("budget-gate-tripped-before-launch"),
                "中止异常必须携带闸断原因供上游归因");
    }

    @Test
    void abortMidRunCancelsUnstartedSiblingsAndStopsWaiting() throws Exception {
        RunBudgetAbort abort = new RunBudgetAbort();
        java.util.concurrent.atomic.AtomicInteger[] hits =
                new java.util.concurrent.atomic.AtomicInteger[4];
        for (int i = 0; i < hits.length; i++) {
            hits[i] = new java.util.concurrent.atomic.AtomicInteger();
        }
        List<Callable<String>> tasks = List.of(
                () -> {
                    hits[0].incrementAndGet();
                    Thread.sleep(50L);
                    abort.trip("first-group-saw-spent-window");
                    return "v0";
                },
                () -> {
                    hits[1].incrementAndGet();
                    Thread.sleep(5_000L);
                    return "v1";
                },
                () -> {
                    hits[2].incrementAndGet();
                    return "v2";
                },
                () -> {
                    hits[3].incrementAndGet();
                    return "v3";
                });
        long start = System.nanoTime();
        GroupFanoutAbortException thrown = assertThrows(GroupFanoutAbortException.class,
                () -> GroupParallelFanout.runAll(tasks, 2, "t641-mid", abort));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertEquals(java.util.Arrays.asList("v0", null, null, null), thrown.landedInOrder(),
                "已落地结果按任务序保留，被取消/未启动槽位为 null");
        assertEquals(1, hits[1].get(), "在飞兄弟只许闸断前那一次入场（随后被中断）");
        assertEquals(0, hits[2].get(), "排队未启动任务在闸后永不被调度");
        assertEquals(0, hits[3].get(), "排队未启动任务在闸后永不被调度");
        assertTrue(elapsedMillis < 2_000L,
                "调用方必须在检查点秒级停等，实际耗时(ms)=" + elapsedMillis);
    }

    @Test
    void serialInlinePathChecksAbortBeforeEveryCall() {
        RunBudgetAbort abort = new RunBudgetAbort();
        java.util.concurrent.atomic.AtomicInteger[] hits =
                new java.util.concurrent.atomic.AtomicInteger[3];
        for (int i = 0; i < hits.length; i++) {
            hits[i] = new java.util.concurrent.atomic.AtomicInteger();
        }
        List<Callable<String>> tasks = List.of(
                () -> {
                    hits[0].incrementAndGet();
                    abort.trip("serial-window-spent");
                    return "v0";
                },
                () -> {
                    hits[1].incrementAndGet();
                    return "v1";
                },
                () -> {
                    hits[2].incrementAndGet();
                    return "v2";
                });
        GroupFanoutAbortException thrown = assertThrows(GroupFanoutAbortException.class,
                () -> GroupParallelFanout.runAll(tasks, 1, "t641-serial", abort));
        assertEquals(java.util.Arrays.asList("v0", null, null), thrown.landedInOrder());
        assertEquals(0, hits[1].get(), "串行谱逐调用前置检查：闸断后的下一任务必须零入场");
        assertEquals(0, hits[2].get());
    }

    @Test
    void nullAbortKeepsLegacyFailClosedPathUntouched() {
        // The legacy 3-arg entry delegates with a null signal, keeping every
        // existing consumer byte-identical (serial dual-spectrum baseline).
        IllegalStateException probe = new IllegalStateException("legacy-vocabulary");
        List<Callable<String>> tasks = List.of(() -> {
            throw probe;
        });
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> GroupParallelFanout.runAll(tasks, 1, "t641-null"));
        assertSame(probe, thrown);
    }

    @Test
    void nestedFanOutSharesOneSignal_innerTasksNeverStartAfterOuterTrip() {
        // mechanism pin: nested planes must ride the SAME run
        // signal (doc group plane → final-bucket plane, run → F5 summary
        // plane). One outer task trips the gate; the sibling's INNER fan-out
        // then stops at its own checkpoints — inner bodies never launch, and
        // the abort vocabulary surfaces to the caller (the doc faces catch
        // exactly this type to land the honest time-budget terminal).
        RunBudgetAbort abort = new RunBudgetAbort();
        int[] innerRuns = new int[1];
        // Deterministic ordering instead of a wall-clock sleep: a fixed
        // delay did not guarantee the trip landed before the sibling's
        // checkpoint on low-core CI runners (identical test bytes passed
        // one run and failed the next). The latch makes the "trip before
        // inner fan-out" precondition explicit: the inner plane must
        // observe the already-tripped signal at its per-task checkpoints.
        CountDownLatch outerTripped = new CountDownLatch(1);
        List<Callable<String>> outer = List.of(
                () -> {
                    abort.trip("outer-A 检出预算闸断");
                    outerTripped.countDown();
                    return "A";
                },
                () -> {
                    assertTrue(outerTripped.await(5L, TimeUnit.SECONDS),
                            "outer task A never ran; the shared pool must start both tasks at parallelism 2");
                    List<Callable<String>> inner = List.of(
                            () -> {
                                innerRuns[0]++;
                                return "B0";
                            },
                            () -> {
                                innerRuns[0]++;
                                return "B1";
                            });
                    return String.valueOf(
                            GroupParallelFanout.runAll(inner, 1, "t641-inner", abort).size());
                });
        assertThrows(GroupFanoutAbortException.class,
                () -> GroupParallelFanout.runAll(outer, 2, "t641-nested", abort));
        assertEquals(0, innerRuns[0], "共享信号已闸断：嵌套扇出的任何任务禁止入场");
    }
}
